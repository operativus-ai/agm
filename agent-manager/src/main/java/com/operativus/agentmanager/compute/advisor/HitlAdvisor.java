package com.operativus.agentmanager.compute.advisor;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.control.service.HumanReviewService;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.ApprovalRequiredException;
import com.operativus.agentmanager.core.model.ApprovalDTO;
import com.operativus.agentmanager.core.model.DecisionPackage;
import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.SecurityPrincipals;
import com.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import com.operativus.agentmanager.core.spi.ToolTierResolverProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Intercepts Spring AI execution to examine intended tool calls and suspend execution if a restricted tool is invoked without a resolved approval.
 * State: Stateless
 */
@Component
public class HitlAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HitlAdvisor.class);
    private final com.operativus.agentmanager.core.registry.ApprovalOperations approvalService;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=hitl}. */
    private final Timer durationTimer;

    /**
     * SPI providers consulted before falling through to the static DESTRUCTIVE_TOOLS set and the
     * configured FinOps gate. Per docs/plans/agm-agentos-tool-parity-impl.md §4 architectural-decisions row + audit
     * Finding 7. Instance field — callers (e.g., {@code AugmentedToolCallbackProvider}) inject this
     * advisor via Spring DI and invoke {@code requiresHitl(...)} as an instance method.
     */
    private final List<ToolTierResolverProvider> tierProviders;

    /**
     * Counter for HITL tier-resolution dispatches that hit a {@link ToolTierResolverProvider}
     * (i.e., a Composio or future external-tool family). Per audit Finding 9.
     */
    private final Counter dispatchCounter;

    /**
     * <b>{@code @Lazy} on {@code approvalService}</b> breaks the constructor cycle
     * {@code HitlAdvisor → ApprovalService → AgentService → AgentClientFactory →
     * AugmentedToolCallbackProvider → HitlAdvisor}. Spring Framework 6 disallows
     * circular references by default, so the cycle MUST be broken at one edge for
     * `@SpringBootTest` integration contexts to load. The approval bean is consumed
     * only at runtime inside {@link #requireApprovalForTool(String, String, String, String, String)}
     * — never during {@code HitlAdvisor} construction — so a lazy proxy is safe.
     * Mirrors the {@code @Lazy ApprovalOperations} precedent in
     * {@code HitlPauseHandler}'s constructor.
     */
    /**
     * REQ-HR-4.5 dual-tracking: {@code agentRepo} + {@code humanReviewService} are also
     * {@code @Lazy} for the same cycle-break reason as {@code approvalService}. The agent
     * lookup + HumanReviewPending row are written only at runtime inside
     * {@link #requireApprovalForTool(String, String, String, String, String)}, never
     * during construction.
     */
    private final AgentRepository agentRepo;
    private final HumanReviewService humanReviewService;

    /**
     * FinOps cost-approval gate (default OFF). When {@code finopsGateEnabled} is true, invoking any
     * tool whose name is in {@code finopsTools} routes to
     * {@link DecisionPackage.DecisionTier#TIER_2_FINOPS_BLOCK} (human cost-approval). Operator-tunable
     * via {@code agm.hitl.finops.enabled} / {@code agm.hitl.finops.tools}; the list is pre-seeded with
     * {@code bulkIngestDocumentationSite} (unbounded full-site crawl + bulk embedding) but does nothing
     * until the gate is enabled. Cost is otherwise bounded org-wide by {@code DailySpendService}.
     */
    private final boolean finopsGateEnabled;
    private final java.util.Set<String> finopsTools;

    public HitlAdvisor(@Lazy com.operativus.agentmanager.core.registry.ApprovalOperations approvalService,
                       @Lazy AgentRepository agentRepo,
                       @Lazy HumanReviewService humanReviewService,
                       MeterRegistry meterRegistry,
                       List<ToolTierResolverProvider> providers,
                       @Value("${agm.hitl.finops.enabled:false}") boolean finopsGateEnabled,
                       @Value("${agm.hitl.finops.tools:}") java.util.Set<String> finopsTools) {
        this.approvalService = approvalService;
        this.agentRepo = agentRepo;
        this.humanReviewService = humanReviewService;
        this.durationTimer = Timer.builder("advisor.duration_ms")
                .tag("advisor", "hitl").register(meterRegistry);
        this.tierProviders = providers == null ? List.of() : List.copyOf(providers);
        this.dispatchCounter = Counter.builder("agent.hitl.composio_dispatch")
                .description("HITL tier dispatches resolved by a ToolTierResolverProvider (e.g., Composio)")
                .register(meterRegistry);
        this.finopsGateEnabled = finopsGateEnabled;
        this.finopsTools = finopsTools == null ? java.util.Set.of()
                : finopsTools.stream().map(String::trim).filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("HitlAdvisor initialized with {} ToolTierResolverProvider(s); finops gate enabled={} tools={}.",
                this.tierProviders.size(), this.finopsGateEnabled, this.finopsTools);
    }

    /**
     * @summary Intercepts synchronous chat client calls for manual approval checks.
     * @logic Delegates to the next advisor in the chain, relying on explicit exception throwing during tool callbacks to halt execution if restricted.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> chain.nextCall(request));
    }
    
    /**
     * @summary Intercepts streaming chat client calls for manual approval checks.
     * @logic Delegates to the next advisor in the reactive chain.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request);
    }

    @Override
    public String getName() {
        return "HitlAdvisor";
    }

    @Override
    public int getOrder() {
        // Should run late in the chain, right before the model execution
        return 0;
    }

    private static final java.util.Set<String> DESTRUCTIVE_TOOLS = java.util.Set.of("delete_database", "truncate_tables", "drop_schema", "destructive_action", "SensitiveOperationsTool", "e2b_execute_python", "run_python");

    /**
     * @summary Decide whether a tool invocation requires HITL approval.
     * @logic Consults registered {@link ToolTierResolverProvider} SPIs first (e.g., Composio
     *     adapter resolves {@code composio_*} tools); first non-empty resolution wins. If a
     *     provider returns Tier 1, HITL is NOT required; Tier 2 / Tier 3, HITL required.
     *     If all providers return empty, falls through to the static DESTRUCTIVE_TOOLS set and the
     *     operator-configured FinOps gate (see {@link #finopsTools}).
     */
    public boolean requiresHitl(String toolName) {
        Optional<DecisionPackage.DecisionTier> resolved = resolveTierFromProviders(toolName);
        if (resolved.isPresent()) {
            return resolved.get() != DecisionPackage.DecisionTier.TIER_1_SAFE;
        }
        return DESTRUCTIVE_TOOLS.contains(toolName)
                || (finopsGateEnabled && finopsTools.contains(toolName));
    }

    /**
     * Iterate registered SPI providers; first non-empty wins. Increments the dispatch
     * counter (audit Finding 9 observability) on each successful provider hit.
     */
    private Optional<DecisionPackage.DecisionTier> resolveTierFromProviders(String toolName) {
        for (ToolTierResolverProvider provider : tierProviders) {
            Optional<DecisionPackage.DecisionTier> tier = provider.resolveTier(toolName);
            if (tier.isPresent()) {
                dispatchCounter.increment();
                if (log.isTraceEnabled()) {
                    log.trace("HITL tier dispatched via SPI for tool={} → tier={}", toolName, tier.get());
                }
                return tier;
            }
        }
        return Optional.empty();
    }

    /**
     * @summary Halts LLM execution when a restricted tool is invoked.
     * @logic Creates a Pending Approval record in the database using the shared ApprovalOperations interface, determining the tier dynamically based on tool name rules. Throws an ApprovalRequiredException to cleanly break the internal ChatClient evaluation loop.
     */
    public void requireApprovalForTool(String toolName, String serializedArgs, String runId, String sessionId, String agentId) {
        // Fail loud on missing context. Earlier code silently substituted "UNKNOWN_RUN" /
        // "UNKNOWN_SESSION" / "UNKNOWN_AGENT" placeholders, which would have poisoned
        // approval-history audit data without operator awareness. HITL fires only inside
        // tool execution which always runs inside an agent run; if the run context is
        // unbound, the call site is misconfigured and that is a 500, not silent fallback.
        if (runId == null || sessionId == null || agentId == null) {
            throw new IllegalStateException(
                    "HITL invoked without bound run context: runId=" + runId
                    + " sessionId=" + sessionId + " agentId=" + agentId
                    + " (tool=" + toolName + "). Tool callbacks must run inside a bound AgentContextHolder scope.");
        }

        log.warn("Tool [{}] requires manual approval. Suspending LLM thread.", toolName);

        // 1. Evaluate Decision Tier dynamically — SPI providers first (e.g., Composio), then static sets.
        com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier tier;
        Optional<DecisionPackage.DecisionTier> resolved = resolveTierFromProviders(toolName);
        if (resolved.isPresent()) {
            tier = resolved.get();
        } else if (DESTRUCTIVE_TOOLS.contains(toolName)) {
            tier = com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier.TIER_3_DESTRUCTIVE;
        } else if (finopsGateEnabled && finopsTools.contains(toolName)) {
            tier = com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK;
        } else {
            tier = com.operativus.agentmanager.core.model.DecisionPackage.DecisionTier.TIER_1_SAFE;
        }

        // Phase 4: Simulated LLM Trace/Impact
        String trace = "Intercepted explicit execution trajectory for tool: " + toolName + ". Action isolated for review.";
        String impact = "Simulated Impact Assessment: Tool execution evaluates to " + tier.name();

        // 2. Create the Pending Approval in the Database
        ApprovalDTO pendingApproval = approvalService.createApprovalRequest(
                runId,
                sessionId,
                agentId,
                toolName,
                serializedArgs,
                "Agent requires permission to execute: " + toolName,
                "SYSTEM",
                trace,
                impact,
                tier
        );

        // REQ-HR-4.5 dual-tracking: if the agent has a HumanReview config with an
        // active pause gate, also write a HumanReviewPending row using the SAME id as
        // the legacy approval. AgentToolResumeHandler then bridges resume back to
        // ApprovalService.resolveApprovalForOrg by that shared id. Failures here are
        // swallowed + logged so the legacy approval path (still primary) is never
        // broken by a dual-tracking write fault.
        writeHumanReviewPendingIfConfigured(pendingApproval.id(), agentId, toolName, runId);

        // 3. Throw Exception to break out of the ChatClient internal loop. The
        //    ToolExecutionExceptionProcessor bean (see ToolCallingExceptionConfig) lists
        //    ApprovalRequiredException in its rethrownExceptions, so this propagates up
        //    to AgentService:327 instead of being stringified and fed back to the LLM.
        throw new ApprovalRequiredException(pendingApproval.id(), toolName, serializedArgs);
    }

    private void writeHumanReviewPendingIfConfigured(String pendingId, String agentId, String toolName, String runId) {
        try {
            Optional<AgentEntity> agentOpt = agentRepo.findById(agentId);
            if (agentOpt.isEmpty()) {
                log.debug("HumanReview dual-track skipped: agent {} not found.", agentId);
                return;
            }
            AgentEntity agent = agentOpt.get();
            HumanReview cfg = agent.getHumanReview();
            if (cfg == null || !cfg.isPauseActive()) {
                return;
            }
            humanReviewService.pauseForWithId(
                    pendingId,
                    HumanReviewSubjectType.AGENT_TOOL_CALL,
                    toolName,
                    runId,
                    agent.getOrgId(),
                    "Agent requires permission to execute: " + toolName,
                    cfg,
                    null,
                    SecurityPrincipals.SYSTEM_PRINCIPAL
            );
        } catch (RuntimeException ex) {
            log.warn("HumanReview dual-track failed for pendingId={} (legacy approval still primary): {}",
                    pendingId, ex.getMessage());
        }
    }
}
