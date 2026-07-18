package ai.operativus.agentmanager.compute.service;


import ai.operativus.agentmanager.compute.config.AgentMdcFilter;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.ToolCallDTO;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.entity.AgentRun;
import ai.operativus.agentmanager.core.model.RequiredAction;
import ai.operativus.agentmanager.core.model.RequiredActionType;
import ai.operativus.agentmanager.core.model.AgentConstants;
import ai.operativus.agentmanager.core.model.MetricConstants;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import ai.operativus.agentmanager.core.exception.ResourceNotFoundException;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.core.registry.RunOperations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import ai.operativus.agentmanager.core.model.enums.RunStatus;

import java.util.Collections;
import java.util.ArrayList;
import ai.operativus.agentmanager.core.registry.KnowledgeIngestionOperations;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain Responsibility: The core engine for the Agent Operating System. Handles dynamic ChatClient construction and orchestrates Single-Agent runs.
 *                        Team execution is delegated to TeamOrchestrationEngine. Background execution is managed by RunExecutionManager.
 *                        Streaming is handled by AgentStreamManager.
 * State: Stateless
 */
@Service
public class AgentService implements AgentOperations {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRegistry agentRegistry;
    private final RunOperations runRepository;
    private final AgentClientFactory agentClientFactory;
    private final KnowledgeIngestionOperations knowledgeIngestionService;
    private final ModelOperations modelService;
    private final ReflectionService reflectionService;
    private final ai.operativus.agentmanager.control.repository.SessionRepository sessionRepository;
    private final Tracer tracer;
    private final TeamOrchestrationEngine teamOrchestrationEngine;
    private final RunExecutionManager runExecutionManager;
    private final AgentStreamManager agentStreamManager;
    private final AgentRunEventBus agentRunEventBus;
    private final AgentRunFinalizer agentRunFinalizer;
    private final ai.operativus.agentmanager.compute.security.ModelRateLimitGuard modelRateLimitGuard;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ai.operativus.agentmanager.control.finops.service.BudgetPolicyService budgetPolicyService;
    private final ai.operativus.agentmanager.control.finops.service.DailySpendService dailySpendService;

    private final int maxConcurrentCalls;
    /** Global default for the granular run-event tier, bound once per run onto
     *  {@link AgentContextHolder#emitGranularEvents} so {@code AgentRunEventBus} can suppress the
     *  high-volume tier when disabled. Property: {@code agm.events.granular-streaming.enabled}. */
    private final boolean granularEventStreamingEnabled;
    private final Counter perAgentCapRejectionCounter;
    private final Counter globalCapRejectionCounter;
    private final MeterRegistry meterRegistry;
    /** Spring-autoconfigured ChatClient.Builder used as the fallback for the
     *  memoryless followup ChatClient (see PR #944 trade-off resolution). */
    private final org.springframework.ai.chat.client.ChatClient.Builder followupClientBuilderFallback;

    public AgentService(AgentRegistry agentRegistry,
                        RunOperations runRepository,
                        AgentClientFactory agentClientFactory,
                        KnowledgeIngestionOperations knowledgeIngestionService,
                        ModelOperations modelService,
                        ReflectionService reflectionService,
                        ai.operativus.agentmanager.control.repository.SessionRepository sessionRepository,
                        Tracer tracer,
                        TeamOrchestrationEngine teamOrchestrationEngine,
                        RunExecutionManager runExecutionManager,
                        AgentStreamManager agentStreamManager,
                        AgentRunEventBus agentRunEventBus,
                        AgentRunFinalizer agentRunFinalizer,
                        ai.operativus.agentmanager.compute.security.ModelRateLimitGuard modelRateLimitGuard,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                        ai.operativus.agentmanager.control.finops.service.BudgetPolicyService budgetPolicyService,
                        ai.operativus.agentmanager.control.finops.service.DailySpendService dailySpendService,
                        org.springframework.ai.chat.client.ChatClient.Builder followupClientBuilderFallback,
                        @org.springframework.beans.factory.annotation.Value("${agent.orchestration.max-concurrent-calls:10}") int maxConcurrentCalls,
                        @org.springframework.beans.factory.annotation.Value("${agm.events.granular-streaming.enabled:true}") boolean granularEventStreamingEnabled,
                        MeterRegistry meterRegistry) {
        this.agentRegistry = agentRegistry;
        this.runRepository = runRepository;
        this.agentClientFactory = agentClientFactory;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.modelService = modelService;
        this.reflectionService = reflectionService;
        this.sessionRepository = sessionRepository;
        this.tracer = tracer;
        this.teamOrchestrationEngine = teamOrchestrationEngine;
        this.runExecutionManager = runExecutionManager;
        this.agentStreamManager = agentStreamManager;
        this.agentRunEventBus = agentRunEventBus;
        this.agentRunFinalizer = agentRunFinalizer;
        this.modelRateLimitGuard = modelRateLimitGuard;
        this.objectMapper = objectMapper;
        this.budgetPolicyService = budgetPolicyService;
        this.dailySpendService = dailySpendService;
        this.followupClientBuilderFallback = followupClientBuilderFallback;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.granularEventStreamingEnabled = granularEventStreamingEnabled;
        this.meterRegistry = meterRegistry;
        this.perAgentCapRejectionCounter = Counter.builder(MetricConstants.ORCHESTRATION_CALLS_REJECTED)
                .tag("scope", "per_agent")
                .description("Orchestration runs rejected for exceeding the per-agent FinOps concurrency cap.")
                .register(meterRegistry);
        this.globalCapRejectionCounter = Counter.builder(MetricConstants.ORCHESTRATION_CALLS_REJECTED)
                .tag("scope", "global")
                .description("Orchestration runs rejected for exceeding the JVM-wide concurrency cap.")
                .register(meterRegistry);
    }


    /**
     * @summary Executes a synchronous, blocking request against a configured Agent or Team of Agents.
     * @logic
     * 1. Validates the Agent's existence and checks system constraints (Concurrency Limits, Maintenance Mode, Vision capabilities).
     * 2. If the Agent is a Team (Router, Swarm, etc.), execution is delegated to TeamOrchestrationEngine.
     * 3. For single agents, constructs the ChatClient via AgentClientFactory and executes a blocking prompt call.
     * 4. Synthesizes the ChatModel's response, intercepts and routes 'PAUSED' tool approval exceptions, and captures LLM telemetry.
     * 5. Spawns an asynchronous task to generate suggested follow-ups (if enabled) and triggers background cognitive reflection.
     */
    @Override
    @io.micrometer.observation.annotation.Observed(name = MetricConstants.AGENT_RUN_OBSERVATION)
    public RunResponse run(String agentId, String userInput, List<org.springframework.ai.content.Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options) {
        AgentDefinition def = agentRegistry.findById(agentId, orgId);
        if (def == null) {
            log.error("Agent not found for ID: {}", agentId);
            throw new ResourceNotFoundException("Agent", agentId);
        }

        checkConcurrencyLimit(agentId, def);

        if (def.maintenanceMode()) {
            log.warn("Attempted to run agent {} while in maintenance mode.", agentId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent is currently in maintenance mode");
        }

        if (media != null && !media.isEmpty()) {
            String modelId = def.modelId();
            if (modelId != null) {
                java.util.Optional<ModelEntity> customModelOpt = modelService.getModelEntityById(modelId);
                if (customModelOpt.isPresent() && Boolean.FALSE.equals(customModelOpt.get().getSupportsVision())) {
                    log.error("Agent {} attempted to process media with model {} which does not support vision.", def.id(), customModelOpt.get().getModelName());
                    throw new BusinessValidationException("Model " + customModelOpt.get().getName() + " does not support vision (image analysis). Please remove images from your request or switch to a vision-capable model.");
                }
            }
        }

        // §6 M-12 Phase 2: per-model rate-limit gate. No-op when the model has no override.
        // Throws RateLimitExceededException → 429 with Retry-After: 60. Sits before
        // ChatClient construction so a rejected request never burns advisor-chain or
        // provider tokens.
        modelRateLimitGuard.acquireOrThrow(def.modelId());

        // FinOps org daily-budget admission gate. No-op unless agentmanager.finops.org-daily-cap-usd
        // is set. Throws DailyBudgetExceededException → 402 BEFORE the run row / any spend when the
        // org has hit its daily cap — complements the mid-flight per-session ceiling. A workflow
        // node funnels through here too, so a capped org's whole workflow is rejected at its first node.
        dailySpendService.enforceOrgDailyCap(orgId);

        log.info("Starting synchronous agent run. Agent ID: {}, Session ID: {}, Model ID: {}", agentId, sessionId, def.modelId());

        String effectiveSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : UUID.randomUUID().toString();

        AgentMdcFilter.setAgentId(agentId);
        AgentMdcFilter.setPhase("RUN_START");
        AgentMdcFilter.populateMdc();

        Integer currentDepth = AgentContextHolder.getOrchestrationDepth();
        if (currentDepth >= 5) {
            log.error("Max orchestration depth exceeded for agent {}", agentId);
            throw new BusinessValidationException("Max orchestration depth (5) reached. Aborting to prevent infinite loops.");
        }

        // Background path: runInBackground pre-saved an agent_runs row and bound its id
        // here. Reuse that row instead of writing a second one — otherwise the API-tracked
        // run never transitions off RUNNING (the inner row gets finalized but the outer
        // is orphaned). Sync path: no binding → generate a fresh id and create a row.
        // See BackgroundRunsRuntimeTest for the regression pin.
        boolean reuseExistingRow = AgentContextHolder.preAllocatedRunId.isBound();
        String executionRunId = reuseExistingRow
                ? AgentContextHolder.preAllocatedRunId.get()
                : UUID.randomUUID().toString();

        ensureSessionExists(effectiveSessionId, agentId, userId, orgId);
        AgentRun runRecord;
        if (reuseExistingRow) {
            runRecord = runRepository.findById(executionRunId)
                    .orElseThrow(() -> new ResourceNotFoundException("Run", executionRunId));
        } else {
            runRecord = new AgentRun(agentId, effectiveSessionId, userInput, userId, orgId);
            runRecord.setId(executionRunId);
            runRecord.setParentRunId(AgentContextHolder.getCurrentRunId());
            runRecord.setStatus(RunStatus.RUNNING);
            runRepository.save(runRecord);
        }

        RunTelemetryAccumulator telemetry = new RunTelemetryAccumulator();
        telemetry.setModelIfAbsent(def.modelId());
        if (def.isTeam() && def.teamMode() != null) {
            telemetry.setOrchestrationStrategy(def.teamMode());
        }

        publishRunLifecycleEvent(AgentRunEventType.RUN_START, executionRunId, agentId, runRecord.getParentRunId(),
                effectiveSessionId, orgId, currentDepth,
                runStartPayload(def, userInput, media, options, generateFollowups));

        Span span = tracer.nextSpan().name("AgentService.run").tag("agentId", agentId).tag("modelId", def.modelId() != null ? def.modelId() : "unknown").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {

            // Tier 2.5 F1 — COORDINATOR + TASKS teams fall through to the single-agent
            // ChatClient path so AgentClientFactory can inject the delegation/task tools +
            // team-member context onto the leader agent's ChatClient. Routing either through
            // TeamOrchestrationEngine.executeSync would re-enter this method (executeSync
            // calls back into AgentService.run via the strategy's runner.run) and recurse
            // to the depth=5 cap. Pre-fix-F1, every COORDINATOR run failed this way; the
            // TASKS branch was added when the synchronous-dispatch refactor of
            // TaskManagementTool.createTask removed the need for a separate worker-loop
            // orchestrator (mirrors how delegate_to_agent works for COORDINATOR).
            //
            // Non-(COORDINATOR|TASKS) teams (SEQUENTIAL, SWARM, ROUTER, PLANNER) continue
            // to short-circuit to executeSync — their orchestrator implementations do not
            // call runner.run on the team agent itself.
            if (def.isTeam()) {
                if ("COORDINATOR".equalsIgnoreCase(def.teamMode())
                        || "TASKS".equalsIgnoreCase(def.teamMode())) {
                    // Validate members + modelId + publish ORCHESTRATOR_DECISION before
                    // building the leader's ChatClient. Validation throws BusinessValidation
                    // on bad config; the outer catch (line ~404) finalizes FAILED with
                    // an actionable message. Pass run-context values explicitly because the
                    // single-agent path's ScopedValue chain (which binds currentRunId, etc.)
                    // is not yet active at this site — we're outside any ScopedValue.where.
                    coordinatorPreflight(def, orgId, userInput, executionRunId, agentId,
                            effectiveSessionId, currentDepth);
                    // Fall through to the single-agent path below.
                } else {
                    try {
                        RunResponse teamResp = ScopedValue.where(AgentContextHolder.telemetry, telemetry)
                                .call(() -> teamOrchestrationEngine.executeSync(
                                        def, userInput, media, effectiveSessionId, userId, orgId,
                                        generateFollowups, this, executionRunId, runRecord, currentDepth));
                        finalizeRunRecord(executionRunId, teamResp, telemetry);
                        publishRunLifecycleEvent(terminalEventType(teamResp), executionRunId, agentId,
                                runRecord.getParentRunId(), effectiveSessionId, orgId, currentDepth,
                                runCompletePayload(teamResp, true));
                        return teamResp;
                    } catch (ai.operativus.agentmanager.core.exception.TeamMemberPausedException tpe) {
                        // F2 — child member returned PAUSED; lift its requiredAction up to the team row.
                        // The CHILD's approval row is already persisted by the child's own AgentService.run
                        // exit path; we only need to mark the team row PAUSED with a pointer to the child.
                        return finalizeTeamPaused(executionRunId, agentId, runRecord, def,
                                effectiveSessionId, orgId, currentDepth, telemetry,
                                ai.operativus.agentmanager.core.model.RequiredAction.teamMemberPause(
                                        tpe.getRequiredAction(), tpe.getPausedRunId()),
                                "Team member paused: agent='" + tpe.getPausedAgentId()
                                        + "' runId='" + tpe.getPausedRunId() + "'.");
                    } catch (ai.operativus.agentmanager.core.exception.SwarmEscalationException se) {
                        // F3 — strategy threw a tier-escalation BEFORE any member ran.
                        return finalizeTeamPaused(executionRunId, agentId, runRecord, def,
                                effectiveSessionId, orgId, currentDepth, telemetry,
                                escalationToTeamPause(se, span),
                                "Team-level swarm escalation blocked: Transition from Tier "
                                        + se.getSourceTier() + " to Tier " + se.getTargetTier()
                                        + " requires human approval.");
                    } catch (ai.operativus.agentmanager.core.exception.ApprovalRequiredException are) {
                        // F3 — strategy threw a tool-approval BEFORE any member ran.
                        return finalizeTeamPaused(executionRunId, agentId, runRecord, def,
                                effectiveSessionId, orgId, currentDepth, telemetry,
                                approvalToTeamPause(are, span),
                                "Team-level tool execution paused: " + are.getToolName()
                                        + ". Requires confirmation.");
                    }
                }
            }

            ChatClient client = agentClientFactory.buildChatClient(def, effectiveSessionId, userId, orgId, options);

            // Resolve the active budget ceiling for this org/agent and bind it into
            // control.security.AgentContextHolder.CONTEXT so GenAiMetricsAdvisor.resolveBudgetCeiling()
            // can enforce it mid-flight. Only bound when a policy row exists; absent policy → no enforcement.
            java.util.Optional<Double> budgetCeiling = budgetPolicyService.findActiveCeiling(orgId, agentId);

            // F4 — inherit approvedTools from outer scope when bound (resume path
            // seeds it with the tool the user just approved). A fresh empty set on
            // every run() invocation would silently drop the resume approval and the
            // gate in AugmentedToolCallbackProvider would re-pause on the same tool.
            ScopedValue.Carrier runScope = ScopedValue.where(AgentContextHolder.toolTraces, Collections.synchronizedList(new ArrayList<ToolCallDTO>()))
                .where(AgentContextHolder.approvedTools,
                        AgentContextHolder.approvedTools.isBound()
                                ? new java.util.HashSet<>(AgentContextHolder.approvedTools.get())
                                : new java.util.HashSet<String>())
                .where(AgentContextHolder.workflowRunId, new String[1])
                .where(AgentContextHolder.orchestrationDepth, currentDepth + 1)
                .where(AgentContextHolder.currentRunId, executionRunId)
                .where(AgentContextHolder.userId, userId != null ? userId : AgentContextHolder.getUserId())
                .where(AgentContextHolder.sessionId, effectiveSessionId)
                .where(AgentContextHolder.orgId, orgId != null ? orgId : AgentContextHolder.getOrgId())
                .where(AgentContextHolder.agentId, agentId)
                .where(AgentContextHolder.agentName, def.name() != null ? def.name() : agentId)
                .where(AgentContextHolder.allowedKnowledgeBaseIds, new ArrayList<>(def.knowledgeBaseIds() != null ? def.knowledgeBaseIds() : Collections.emptyList()))
                .where(AgentContextHolder.requiresEncryption, def.complianceTier() == ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_2_STRICT)
                // Resolve the granular-event-streaming decision ONCE per run and bind it so every
                // event emission within this scope (and snapshot-propagated forked threads) reads a
                // cheap bound boolean. AgentRunEventBus gates the granular tier on it.
                .where(AgentContextHolder.emitGranularEvents, granularEventStreamingEnabled)
                .where(AgentContextHolder.telemetry, telemetry);
            if (budgetCeiling.isPresent()) {
                runScope = runScope.where(
                    ai.operativus.agentmanager.control.security.AgentContextHolder.CONTEXT,
                    new ai.operativus.agentmanager.control.security.AgentContextHolder.AgentContext(
                        null, null, budgetCeiling.get(), null));
            }
            RunResponse responseObj = runScope.call(() -> {
                    AgentContextHolder.populateMdcFromScopedValues();
                    try {
                    String localResponse = "";
                    List<ToolCallDTO> traces = Collections.emptyList();
                    Map<String, Object> metadata = new java.util.HashMap<>();
                    metadata.put("model", def.modelId());

                    String currentInput = userInput;
                    final String finalInput = currentInput;

                    try {
                        AgentContextHolder.clear();
                        org.springframework.ai.chat.client.ChatClientResponse ccr = client.prompt()
                                .user(u -> {
                                    u.text(finalInput);
                                    if (media != null) {
                                        u.media(media.toArray(new org.springframework.ai.content.Media[0]));
                                    }
                                })
                                .call()
                                .chatClientResponse();
                        localResponse = ccr != null && ccr.chatResponse() != null
                                && ccr.chatResponse().getResult() != null
                                && ccr.chatResponse().getResult().getOutput() != null
                                ? ccr.chatResponse().getResult().getOutput().getText() : "";
                        // Previously propagated HallucinationDetectionAdvisor's risk-score /
                        // signals / warning from response.context() into the run metadata.
                        // The advisor was dropped pre-launch (see
                        // docs/analysis/agm-advisor-chain-audit.md) — the score was consumed
                        // by nothing downstream and the advisor added an extra LLM call per
                        // response. If a future feature wants advisor-enriched context keys
                        // surfaced to the run metadata, restore the same pattern here.
                        traces = AgentContextHolder.getTraces();

                        if (Boolean.TRUE.equals(generateFollowups)) {
                            try {
                                // Bug #7 part 1 (PR #944): previously the prompt said "Analyze the
                                // conversation history and suggest followups" — the LLM treated that
                                // instruction itself as the topic and returned meta-questions about
                                // the followup mechanism. The prompt now passes the prior turn
                                // explicitly and forbids meta-questions.
                                //
                                // Bug #7 part 2 (this code): the previous fix still went through the
                                // agent's main `client`, so MessageChatMemoryAdvisor.after() appended
                                // the followup turn to persistent chat memory. Subsequent user turns
                                // then saw a meta-conversation about followups in their history,
                                // which biased the agent's view of the user's intent. Now we build a
                                // memoryless ChatClient on the SAME model as the agent via
                                // AgentClientFactory.buildOrchestrationChatClient(def, builder) —
                                // it carries only the safety advisor chain (prompt injection, PII,
                                // content safety, metrics), no memory, no tools, no RAG. The followup
                                // turn never reaches persistent_chat_memory.
                                String fpUserMessage = """
                                        ORIGINAL USER QUESTION:
                                        %s

                                        AGENT'S ANSWER:
                                        %s

                                        Generate exactly 3 concise follow-up questions the same user is most likely to ask NEXT, given the topic and content of the ORIGINAL USER QUESTION above. Questions must stay on the topic of that original question.
                                        """.formatted(
                                                userInput == null ? "" : userInput,
                                                localResponse == null ? "" : localResponse);
                                ChatClient followupClient = agentClientFactory
                                        .buildOrchestrationChatClient(def, followupClientBuilderFallback);
                                String fpResponse = followupClient.prompt()
                                    .user(fpUserMessage)
                                    .system("You are a follow-up suggestion generator. You will be given a user question and the agent's answer as explicit text. Your sole job is to predict the next 3 questions THAT USER would ask, on the same topic. Do NOT generate meta-questions about how you suggest follow-ups, about the followup mechanism, or about the conversation itself. Do NOT reference \"the conversation history\". Return EXACTLY a complete JSON array of strings, e.g. [\"question 1?\", \"question 2?\", \"question 3?\"], with no surrounding prose or markdown.")
                                    .call().content();
                                metadata.put("followUpSuggestions", fpResponse);
                            } catch (Exception ex) {
                                log.warn("Failed to generate predictive followups for run: {}", ex.getMessage());
                            }
                        }

                        reflectionService.reflectOnRun(userInput, localResponse, userId, executionRunId, agentId, effectiveSessionId);

                        return new RunResponse(
                                executionRunId,
                                effectiveSessionId,
                                localResponse,
                                metadata,
                                traces,
                                Collections.emptyList(),
                                RunStatus.COMPLETED,
                                RunMetricsBuilder.fromTelemetry(telemetry)
                        );
                    } catch (ai.operativus.agentmanager.core.exception.SwarmHandOffException e) {
                        metadata.put("swarmHandOffTarget", e.getTargetAgentId());
                        metadata.put("swarmHandOffContext", e.getHandOffContext());
                        return new RunResponse(
                            executionRunId,
                            effectiveSessionId,
                            "Handoff initiated to " + e.getTargetAgentId() + ".",
                            metadata,
                            AgentContextHolder.getTraces(),
                            Collections.emptyList(),
                            RunStatus.COMPLETED,
                            RunMetricsBuilder.fromTelemetry(telemetry)
                        );
                    } catch (ai.operativus.agentmanager.core.exception.SwarmEscalationException e) {
                        String currentTraceId = span != null ? span.context().traceId() : AgentConstants.NO_TRACE;
                        StringBuilder lineageBuilder = new StringBuilder();
                        AgentContextHolder.getTraces().forEach(t -> lineageBuilder.append(t.toolName()).append(" -> "));
                        String reasoningLineage = lineageBuilder.isEmpty() ? "Direct Execution" : lineageBuilder.toString();

                        metadata.put("requiredAction", RequiredAction.swarmEscalation(
                                e.getSourceAgentId(),
                                e.getTargetAgentId(),
                                e.getSourceTier(),
                                e.getTargetTier(),
                                e.getEscalationId(),
                                currentTraceId,
                                reasoningLineage,
                                "Depth: " + AgentContextHolder.getOrchestrationDepth()));
                        return new RunResponse(
                            executionRunId,
                            effectiveSessionId,
                            "Swarm escalation blocked: Transition from Tier " + e.getSourceTier()
                                + " to Tier " + e.getTargetTier() + " requires human approval.",
                            metadata,
                            AgentContextHolder.getTraces(),
                            Collections.emptyList(),
                            RunStatus.PAUSED,
                            RunMetricsBuilder.fromTelemetry(telemetry)
                        );
                    } catch (ai.operativus.agentmanager.core.exception.ApprovalRequiredException e) {
                        String currentTraceId = span != null ? span.context().traceId() : AgentConstants.NO_TRACE;
                        StringBuilder lineageBuilder = new StringBuilder();
                        AgentContextHolder.getTraces().forEach(t -> lineageBuilder.append(t.toolName()).append(" -> "));
                        String reasoningLineage = lineageBuilder.isEmpty() ? "Direct Execution" : lineageBuilder.toString();

                        metadata.put("requiredAction", RequiredAction.toolApproval(
                                e.getToolName(),
                                e.getToolArgs(),
                                e.getApprovalId(),
                                currentTraceId,
                                reasoningLineage,
                                "Depth: " + AgentContextHolder.getOrchestrationDepth()));
                        return new RunResponse(
                            executionRunId,
                            effectiveSessionId,
                            "Tool execution paused: " + e.getToolName() + ". Requires confirmation.",
                            metadata,
                            AgentContextHolder.getTraces(),
                            Collections.emptyList(),
                            RunStatus.PAUSED,
                            RunMetricsBuilder.fromTelemetry(telemetry)
                        );
                    } catch (ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException e) {
                        throw e;
                    } catch (Exception e) {
                        log.error("Exception during agent execution: {}", e.getMessage(), e);
                        if (AgentErrorClassifier.isContextLimitError(e)) {
                            log.warn("Context window limit hit for agent {} on model {}", agentId, def.modelId());
                            return new RunResponse(
                                executionRunId,
                                effectiveSessionId,
                                "\u26a0\ufe0f This conversation has exceeded the model's context window. Please start a new session to continue, or ask me to summarize the conversation first.",
                                metadata,
                                AgentContextHolder.getTraces(),
                                Collections.emptyList(),
                                RunStatus.FAILED,
                                RunMetricsBuilder.fromTelemetry(telemetry)
                            );
                        }
                        if (AgentErrorClassifier.isQuotaOrRateLimitError(e)) {
                            log.warn("Quota or rate limit exceeded for agent {} on model {}", agentId, def.modelId());
                            // gap #8 \u2014 try per-agent fallback chain in order before giving up.
                            // Each fallback gets the same advisor wiring (tools, RAG, PII) \u2014 only
                            // the model id is swapped via AgentClientFactory.buildChatClientForFallback.
                            RunResponse fallbackResp = tryFallbackChain(def, effectiveSessionId, userId, orgId,
                                    options, finalInput, media, executionRunId, metadata, telemetry, agentId);
                            if (fallbackResp != null) {
                                return fallbackResp;
                            }
                            return new RunResponse(
                                executionRunId,
                                effectiveSessionId,
                                "\u26a0\ufe0f The AI provider's rate limit or token quota has been exceeded. Please wait a moment and try again, or contact your administrator if this persists.",
                                metadata,
                                AgentContextHolder.getTraces(),
                                Collections.emptyList(),
                                RunStatus.FAILED,
                                RunMetricsBuilder.fromTelemetry(telemetry)
                            );
                        }
                        throw new RuntimeException(e);
                    }
                    } finally {
                        AgentContextHolder.clearMdcFromScopedValues();
                    }
                });

            finalizeRunRecord(executionRunId, responseObj, telemetry);

            AgentMdcFilter.setPhase("RUN_COMPLETE");
            log.info("Completed synchronous agent run. Agent ID: {}, Session ID: {}, Model ID: {}", agentId, sessionId, def.modelId());
            publishRunLifecycleEvent(terminalEventType(responseObj), executionRunId, agentId,
                    runRecord.getParentRunId(), effectiveSessionId, orgId, currentDepth,
                    runCompletePayload(responseObj, false));
            return responseObj;
        } catch (Exception ex) {
            if (span != null) {
               span.error(ex);
            }
            telemetry.recordError(ex.getClass().getSimpleName(), ex.getMessage());
            agentRunFinalizer.finalizeRun(executionRunId, RunStatus.FAILED,
                    "Error: " + ex.getMessage(), null, telemetry);
            publishRunLifecycleEvent(AgentRunEventType.RUN_FAILED, executionRunId, agentId,
                    runRecord.getParentRunId(), effectiveSessionId, orgId, currentDepth,
                    runFailedPayload(ex));
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(ex);
        } finally {
            if (span != null) {
               span.end();
            }
        }
    }

    /**
     * Tier 2.5 F1 preflight for COORDINATOR-mode team agents. Validates that the team has
     * a non-blank modelId (the leader agent's ChatClient cannot be built without one) and
     * that it has at least one valid, active member to delegate to. Publishes the
     * ORCHESTRATOR_DECISION event so downstream observers see the team-shape that the
     * leader agent will operate against. Throws BusinessValidationException on bad config;
     * the caller's outer catch finalizes the run as FAILED with the actionable message.
     *
     * Replaces the validation + event-publish that previously lived in
     * CoordinatorOrchestrator.execute (which was removed when F1 made the orchestrator
     * class redundant — its purpose was just to delegate, but the leader's ChatClient
     * already does the delegation autonomously via the delegate_to_agent tool injected
     * by AgentClientFactory:221-229).
     */
    private void coordinatorPreflight(AgentDefinition def, String orgId, String userInput,
                                      String executionRunId, String agentId,
                                      String sessionId, Integer currentDepth) {
        if (def.modelId() == null || def.modelId().isBlank()) {
            throw new BusinessValidationException(
                    "COORDINATOR team '" + def.id() + "' has no modelId set — the leader agent's "
                            + "ChatClient cannot be built. Set modelId on the team agent and retry.");
        }

        if (def.members() == null || def.members().isEmpty()) {
            throw new BusinessValidationException(
                    "Coordinator failed: Team '" + def.id() + "' has no members defined.");
        }

        List<AgentDefinition> activeMembers = def.members().stream()
                .map(id -> agentRegistry.findById(id, orgId))
                .filter(java.util.Objects::nonNull)
                .filter(a -> a.active() && !a.maintenanceMode())
                .filter(a -> !a.id().equals(def.id()))
                .toList();

        if (activeMembers.isEmpty()) {
            throw new BusinessValidationException(
                    "Coordinator failed: No valid, active members found for team '" + def.id() + "'.");
        }

        List<String> memberIds = activeMembers.stream().map(AgentDefinition::id).toList();
        log.info("orchestration.delegation mode=COORDINATOR agent={} members=[{}] inputSize={}",
                def.id(),
                String.join(",", memberIds),
                userInput != null ? userInput.length() : 0);

        Map<String, Object> decisionPayload = new java.util.HashMap<>();
        decisionPayload.put("mode", "COORDINATOR");
        decisionPayload.put("rootAgentId", def.id());
        decisionPayload.put("memberCount", memberIds.size());
        decisionPayload.put("memberIds", memberIds);
        decisionPayload.put("inputLength", userInput != null ? userInput.length() : 0);

        try {
            AgentRunEvent event = new AgentRunEvent(
                    AgentRunEventType.ORCHESTRATOR_DECISION,
                    executionRunId,
                    agentId,
                    null,
                    sessionId,
                    orgId,
                    currentDepth,
                    decisionPayload,
                    java.time.Instant.now());
            agentRunEventBus.publish(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish ORCHESTRATOR_DECISION event runId={}", executionRunId, ex);
        }
    }

    /**
     * Tier 2.5 F2/F3 — finalize the team-level run row as PAUSED with the lifted
     * requiredAction payload. Called from the team branch's catch blocks. Persists
     * the run, publishes RUN_PAUSED, and returns a RunResponse the caller can hand
     * back to its own caller (HTTP layer / async runner). MVP: team-level runs
     * cannot be resumed via continueRun — see continueRun's team-rejection branch.
     */
    private RunResponse finalizeTeamPaused(String executionRunId, String agentId, AgentRun runRecord,
                                           AgentDefinition def, String effectiveSessionId, String orgId,
                                           Integer currentDepth, RunTelemetryAccumulator telemetry,
                                           ai.operativus.agentmanager.core.model.RequiredAction requiredAction,
                                           String pauseMessage) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("requiredAction", requiredAction);
        metadata.put("model", def.modelId());

        RunResponse resp = new RunResponse(
                executionRunId,
                effectiveSessionId,
                pauseMessage,
                metadata,
                AgentContextHolder.getTraces(),
                Collections.emptyList(),
                RunStatus.PAUSED,
                RunMetricsBuilder.fromTelemetry(telemetry));

        finalizeRunRecord(executionRunId, resp, telemetry);
        publishRunLifecycleEvent(AgentRunEventType.RUN_PAUSED, executionRunId, agentId,
                runRecord.getParentRunId(), effectiveSessionId, orgId, currentDepth,
                runCompletePayload(resp, true));
        return resp;
    }

    private ai.operativus.agentmanager.core.model.RequiredAction escalationToTeamPause(
            ai.operativus.agentmanager.core.exception.SwarmEscalationException e, Span span) {
        String currentTraceId = (span != null && span.context() != null) ? span.context().traceId() : AgentConstants.NO_TRACE;
        return ai.operativus.agentmanager.core.model.RequiredAction.swarmEscalation(
                e.getSourceAgentId(),
                e.getTargetAgentId(),
                e.getSourceTier(),
                e.getTargetTier(),
                e.getEscalationId(),
                currentTraceId,
                "Team strategy",
                "Depth: " + AgentContextHolder.getOrchestrationDepth());
    }

    /**
     * Tier 2.5 F2/F3 — reject continueRun on a team-level paused run with an actionable
     * message pointing the caller at either the child runId (F2) or re-invocation (F3).
     * The directed resume URL matches the child's RequiredAction.type:
     * /api/v1/approvals/{approvalId}/resolve for TOOL_APPROVAL,
     * /api/v1/escalations/{escalationId}/resolve for SWARM_ESCALATION_APPROVAL.
     */
    private void rejectIfTeamPause(AgentRun run) {
        String reqAction = run.getRequiredAction();
        ai.operativus.agentmanager.core.model.RequiredAction parsed = null;
        String pausedChildRunId = null;
        if (reqAction != null && !reqAction.isBlank()) {
            try {
                parsed = objectMapper.readValue(reqAction, ai.operativus.agentmanager.core.model.RequiredAction.class);
                if (parsed != null) {
                    pausedChildRunId = parsed.pausedChildRunId();
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Fall through; the existing resume parser will surface malformed payloads.
            }
        }

        if (pausedChildRunId != null) {
            throw new BusinessValidationException(
                    "Team-level resume not supported. Resume the paused child run at "
                            + pausedChildRunId + " via " + describeChildResumeEndpoint(parsed)
                            + " (body: {\"decision\":\"APPROVED\"|\"REJECTED\"}). "
                            + "After the child resumes and completes, re-invoke the team agent from scratch.");
        }

        if (run.getAgentId() != null) {
            try {
                AgentDefinition def = agentRegistry.findById(run.getAgentId(), run.getOrgId());
                if (def != null && def.isTeam()) {
                    throw new BusinessValidationException(
                            "Team-level escalation cannot be resumed. The team's strategy threw the escalation "
                                    + "before any member ran. Re-invoke the team agent from scratch after addressing "
                                    + "the escalation cause (e.g., raise the source agent's tier or reconfigure transition).");
                }
            } catch (BusinessValidationException bve) {
                throw bve;
            } catch (RuntimeException ignored) {
                // Registry lookup failure is not fatal — fall through to the normal resume path.
            }
        }
    }

    /**
     * Produces the typed-resume URL fragment for the paused child of a team-level run.
     * The deleted runId-keyed {@code POST /runs/{id}/continue} endpoint (removed in PR #352)
     * was replaced by two typed sister surfaces; this helper picks the right one based on
     * the child's {@link RequiredActionType}.
     */
    private static String describeChildResumeEndpoint(ai.operativus.agentmanager.core.model.RequiredAction parsed) {
        if (parsed == null || parsed.type() == null) {
            return "the typed resolve endpoint matching the child's RequiredAction.type "
                    + "(POST /api/v1/approvals/{approvalId}/resolve or POST /api/v1/escalations/{escalationId}/resolve)";
        }
        return switch (parsed.type()) {
            case TOOL_APPROVAL -> parsed.approvalId() != null
                    ? "POST /api/v1/approvals/" + parsed.approvalId() + "/resolve"
                    : "POST /api/v1/approvals/{approvalId}/resolve";
            case SWARM_ESCALATION_APPROVAL -> parsed.escalationId() != null
                    ? "POST /api/v1/escalations/" + parsed.escalationId() + "/resolve"
                    : "POST /api/v1/escalations/{escalationId}/resolve";
            case TEAM_MEMBER_DISPATCH_APPROVAL -> parsed.approvalId() != null
                    ? "POST /api/v1/approvals/" + parsed.approvalId() + "/decide"
                    : "POST /api/v1/approvals/{approvalId}/decide";
        };
    }

    private ai.operativus.agentmanager.core.model.RequiredAction approvalToTeamPause(
            ai.operativus.agentmanager.core.exception.ApprovalRequiredException e, Span span) {
        String currentTraceId = (span != null && span.context() != null) ? span.context().traceId() : AgentConstants.NO_TRACE;
        return ai.operativus.agentmanager.core.model.RequiredAction.toolApproval(
                e.getToolName(),
                e.getToolArgs(),
                e.getApprovalId(),
                currentTraceId,
                "Team strategy",
                "Depth: " + AgentContextHolder.getOrchestrationDepth());
    }

    /**
     * gap #8 — iterate the per-agent fallback model chain after a quota/rate-limit
     * error on the primary model. Returns the first successful {@link RunResponse},
     * or {@code null} when no fallback succeeds (caller falls back to the original
     * FAILED-with-message behavior). Each fallback gets the same advisor wiring
     * (tools, RAG, PII) — only the model id is swapped via
     * {@link AgentClientFactory#buildChatClientForFallback}.
     *
     * <p>Errors thrown by a fallback are caught per-iteration; only quota/rate-limit
     * errors continue to the next entry (non-rate-limit errors break the chain so
     * we don't burn the entire fallback budget on a deterministic failure mode).
     */
    private RunResponse tryFallbackChain(AgentDefinition def, String sessionId, String userId,
            String orgId, RunOptions options, String userInput, List<org.springframework.ai.content.Media> media,
            String executionRunId, Map<String, Object> metadata, RunTelemetryAccumulator telemetry,
            String agentId) {
        List<String> chain = def.fallbackModelIds();
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        for (String fallbackModelId : chain) {
            if (fallbackModelId == null || fallbackModelId.isBlank()
                    || fallbackModelId.equals(def.modelId())) {
                continue;
            }
            try {
                log.warn("Retrying agent {} with fallback model '{}' after quota on primary '{}'",
                        agentId, fallbackModelId, def.modelId());
                ChatClient fallbackClient = agentClientFactory.buildChatClientForFallback(
                        def, sessionId, userId, orgId, options, fallbackModelId);
                AgentContextHolder.clear();
                String content = fallbackClient.prompt()
                        .user(u -> {
                            u.text(userInput);
                            if (media != null) {
                                u.media(media.toArray(new org.springframework.ai.content.Media[0]));
                            }
                        })
                        .call()
                        .content();
                metadata.put("model", fallbackModelId);
                metadata.put("primaryModel", def.modelId());
                metadata.put("fallbackUsed", true);
                return new RunResponse(
                        executionRunId,
                        sessionId,
                        content,
                        metadata,
                        AgentContextHolder.getTraces(),
                        Collections.emptyList(),
                        RunStatus.COMPLETED,
                        RunMetricsBuilder.fromTelemetry(telemetry)
                );
            } catch (Exception fbEx) {
                if (AgentErrorClassifier.isQuotaOrRateLimitError(fbEx)) {
                    log.warn("Fallback model '{}' also rate-limited for agent {}; trying next",
                            fallbackModelId, agentId);
                    continue;
                }
                log.error("Fallback model '{}' failed for agent {} with non-quota error; breaking chain",
                        fallbackModelId, agentId, fbEx);
                break;
            }
        }
        log.warn("All fallback models exhausted for agent {} (chain size={})", agentId, chain.size());
        return null;
    }

    private void finalizeRunRecord(String runId, RunResponse resp, RunTelemetryAccumulator telemetry) {
        String requiredAction = null;
        if (resp.metadata() != null && resp.metadata().containsKey("requiredAction")) {
            // Serialize as JSON via Jackson (writes the typed RequiredAction record). The
            // historical Map.toString() format broke the resume parser whenever args contained
            // commas (and depended on JVM-specific Map output) — see audit Tier 2.4 F6.
            try {
                requiredAction = objectMapper.writeValueAsString(resp.metadata().get("requiredAction"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize requiredAction for run " + runId, e);
            }
        }
        agentRunFinalizer.finalizeRun(runId, resp.status(), resp.content(), requiredAction, telemetry);
    }

    private void publishRunLifecycleEvent(AgentRunEventType type, String runId, String agentId,
                                          String parentRunId, String sessionId, String orgId,
                                          Integer depth, Map<String, Object> payload) {
        try {
            agentRunEventBus.publish(new AgentRunEvent(
                    type, runId, agentId, parentRunId, sessionId, orgId, depth, payload, java.time.Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("AgentRunEventBus publish failed type={} runId={}", type, runId, ex);
        }
    }

    private static AgentRunEventType terminalEventType(RunResponse resp) {
        return switch (resp.status()) {
            case FAILED -> AgentRunEventType.RUN_FAILED;
            case PAUSED -> AgentRunEventType.RUN_PAUSED;
            default -> AgentRunEventType.RUN_COMPLETE;
        };
    }

    private static Map<String, Object> runStartPayload(AgentDefinition def, String userInput,
                                                       List<org.springframework.ai.content.Media> media,
                                                       RunOptions options, Boolean generateFollowups) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("agentName", def.name());
        p.put("modelId", def.modelId());
        p.put("isTeam", def.isTeam());
        p.put("inputLength", userInput == null ? 0 : userInput.length());
        p.put("mediaCount", media == null ? 0 : media.size());
        p.put("generateFollowups", Boolean.TRUE.equals(generateFollowups));
        if (options != null) p.put("hasRunOptions", true);
        return p;
    }

    private static Map<String, Object> runCompletePayload(RunResponse resp, boolean viaTeam) {
        Map<String, Object> p = new java.util.HashMap<>();
        String agentName = AgentContextHolder.getAgentName();
        if (agentName != null) p.put("agentName", agentName);
        p.put("status", resp.status() != null ? resp.status().name() : null);
        p.put("contentLength", resp.content() == null ? 0 : resp.content().length());
        p.put("toolCallCount", resp.tools() == null ? 0 : resp.tools().size());
        p.put("viaTeam", viaTeam);
        if (resp.metadata() != null) {
            p.put("metadataKeys", new ArrayList<>(resp.metadata().keySet()));
        }
        return p;
    }

    private static Map<String, Object> runFailedPayload(Throwable ex) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("errorClass", ex.getClass().getName());
        p.put("errorMessage", ex.getMessage());
        p.put("contextLimit", AgentErrorClassifier.isContextLimitError(ex));
        p.put("quotaOrRateLimit", AgentErrorClassifier.isQuotaOrRateLimitError(ex));
        return p;
    }

    public RunResponse run(String agentId, String userInput, String sessionId) {
        return run(agentId, userInput, null, sessionId, null, null, false, null);
    }

    /**
     * @summary Queues an Agent execution on a Virtual Thread, returning a tracking ID for asynchronous polling.
     * @logic Persists an initial AgentRun in QUEUED state, then delegates to RunExecutionManager for background submission.
     */
    @Override
    public String runInBackground(String agentId, String userInput, List<org.springframework.ai.content.Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options) {
        AgentDefinition def = agentRegistry.findById(agentId, orgId);
        if (def == null) {
            throw new ResourceNotFoundException("Agent", agentId);
        }

        // C01: reject background runs at HTTP layer when agent is quarantined. Without this
        // guard, the run row is saved as QUEUED and only fails inside the virtual thread —
        // confusing UX (200 OK + runId that silently transitions to FAILED). Mirrors the
        // synchronous run() guard at line 122. Status code matches the established 503
        // convention pinned by AgentServiceTest.
        if (Boolean.TRUE.equals(def.maintenanceMode())) {
            log.warn("Rejecting background run for quarantined agent {}", agentId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent is currently in maintenance mode");
        }

        checkConcurrencyLimit(agentId);

        AgentMdcFilter.setAgentId(agentId);
        AgentMdcFilter.setPhase("BACKGROUND_QUEUE");
        AgentMdcFilter.populateMdc();
        log.info("Queuing background agent run. Agent ID: {}, Session ID: {}", agentId, sessionId);

        ensureSessionExists(sessionId, agentId, userId, orgId);
        AgentRun run = new AgentRun(agentId, sessionId, userInput, userId, orgId);
        run.setParentRunId(AgentContextHolder.getCurrentRunId());
        runRepository.save(run);

        // Bind preAllocatedRunId so the inner run() reuses this row instead of inserting
        // a duplicate. ScopedValue.where(...).call(...) binds on the VT itself when the
        // lambda fires (no cross-thread propagation needed). See AgentContextHolder.
        final String preAllocId = run.getId();
        return runExecutionManager.submit(run, () -> {
            try {
                return ScopedValue.where(AgentContextHolder.preAllocatedRunId, preAllocId)
                        .call(() -> run(agentId, userInput, media, sessionId, userId, orgId, generateFollowups, options));
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void cancelRun(String runId) {
        // §79 RBAC pattern — return 404 (not 403) when the run belongs to another tenant.
        // 403 would leak tenant-membership; 404 makes cross-tenant indistinguishable from
        // non-existent. A null bound orgId is the super-admin / bootstrap path (matches
        // the existing pattern elsewhere: see AgentsController.resolveCallerOrgId fallback).
        String callerOrgId = AgentContextHolder.getOrgId();
        if (callerOrgId != null && !callerOrgId.isBlank()) {
            var existing = runRepository.findById(runId);
            if (existing.isPresent()
                    && !java.util.Objects.equals(existing.get().getOrgId(), callerOrgId)) {
                throw new ResourceNotFoundException("Run", runId);
            }
        }
        runExecutionManager.cancel(runId);
    }

    /**
     * @summary Resumes a previously paused AgentRun following Human-in-the-Loop (HITL) manual intervention.
     * @logic
     * 1. Verifies the run is currently in a PAUSED state.
     * 2. If the user approved the action, extracts the specific tool name from the required_action payload.
     * 3. Temporarily overrides the AgentContextHolder with the approved tool, and synchronously re-runs the LLM prompt.
     * 4. Updates the run status to COMPLETED or CANCELLED based on the user's action.
     */
    @Override
    public RunResponse continueRun(String runId, String action) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Run", runId));

        if (run.getStatus() != RunStatus.PAUSED) {
             throw new BusinessValidationException("Run is not in PAUSED state. Current state: " + run.getStatus());
        }

        // Tier 2.5 F2/F3 — team-level paused runs are not resumable in this version.
        // F2 case: pausedChildRunId on the requiredAction points at the child member that
        // holds the actual approval row; resume there. F3 case: a strategy threw before
        // any member ran — there is no child to resume; the team must be re-invoked.
        rejectIfTeamPause(run);

        // PR-6 observability: wrap the resume body in a tracer span so resume operations
        // appear as siblings of the original run's span in the trace UI. The span is closed
        // in a finally block; outcome (approved/rejected/error) is set as a tag at each exit
        // path. Exceptions escape unchanged — the catch-and-rethrow only marks the span and
        // increments the error counter before rethrowing.
        Span span = tracer.nextSpan().name(MetricConstants.SPAN_HITL_RESUME)
                .tag(MetricConstants.ATTR_RUN_ID, runId)
                .tag("decision", action == null ? "n/a" : action)
                .start();

        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            // F18 — ApprovalService.resolveApprovalForOrg passes RunStatus.APPROVED.name()
            // ("APPROVED", 8 chars). The historical literal "APPROVE" (7 chars) made every
            // resume fall into the REJECT branch and finalize as CANCELLED. Compare against
            // the enum so a future RunStatus rename catches at compile time.
            if (RunStatus.APPROVED.name().equalsIgnoreCase(action)) {
                // F6 — parse the JSON-serialized RequiredAction. Historical Map.toString()
                // substring slicing broke whenever toolArgs contained commas; the typed
                // record + Jackson removes that brittleness.
                String reqAction = run.getRequiredAction();
                String toolName = "unknown_tool";
                if (reqAction != null && !reqAction.isBlank()) {
                    try {
                        RequiredAction parsed = objectMapper.readValue(reqAction, RequiredAction.class);
                        if (parsed != null && parsed.tool() != null && !parsed.tool().isBlank()) {
                            toolName = parsed.tool();
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        log.warn("Failed to parse requiredAction JSON for run {}: {}",
                                runId, e.getMessage());
                    }
                }
                span.tag("tool", toolName);

                // F3a + F4 + ergonomics — bind the inner run() through ScopedValue:
                //   preAllocatedRunId → run() reuses the paused row instead of inserting
                //     a duplicate (see line 181-197 reuseExistingRow branch).
                //   approvedTools → seeded with the resumed tool so the gate at
                //     AugmentedToolCallbackProvider.adaptCallback bypasses HitlAdvisor for
                //     this specific tool. AgentContextHolder.approveTool() outside a bound
                //     scope is a no-op, hence the historical bug; binding here makes the
                //     approval observable inside the inner run(). The inner run()'s own
                //     ScopedValue chain (line 227) inherits this set when bound.
                //   userId/orgId — load from the AgentRun entity instead of nulls so the
                //     inner run()'s strict-tenant agent lookup succeeds and audit/telemetry
                //     is attributed to the original requester.
                final String preAllocId = runId;
                final String resumedTool = toolName;
                final java.util.Set<String> resumeApprovedTools =
                        new java.util.HashSet<>(java.util.Set.of(resumedTool));
                try {
                    RunResponse response = ScopedValue
                            .where(AgentContextHolder.preAllocatedRunId, preAllocId)
                            .where(AgentContextHolder.approvedTools, resumeApprovedTools)
                            .call(() -> run(run.getAgentId(), run.getInput(), null,
                                    run.getSessionId(), run.getUserId(), run.getOrgId(),
                                    false, null));
                    // Route the paused row's terminal write through the finalizer (the contract
                    // owner). Telemetry from the resume attempt was bound inside run()'s scope
                    // and is not accessible here — pass null and let the inner finalize keep
                    // its own row's telemetry intact.
                    agentRunFinalizer.finalizeRun(runId, response.status(), response.content(), null, null);
                    span.tag("outcome", "approved");
                    recordResumeOutcome("approved", resumedTool);
                    return response;
                } catch (RuntimeException re) {
                    span.tag("outcome", "error").error(re);
                    recordResumeOutcome("error", resumedTool);
                    throw re;
                } catch (Exception e) {
                    span.tag("outcome", "error").error(e);
                    recordResumeOutcome("error", resumedTool);
                    throw new RuntimeException(e);
                }
            } else {
                 agentRunFinalizer.finalizeRun(runId, RunStatus.CANCELLED,
                         "User rejected the requested action.", null, null);
                 // Cancellation path: telemetry is intentionally not bound (no LLM execution
                 // happened). Use RunMetricsBuilder.noTelemetry() — explicit construction for
                 // a path where the accumulator was never expected to exist (NOT a defensive
                 // fallback for impossible state per A19).
                 span.tag("outcome", "rejected");
                 recordResumeOutcome("rejected", "n/a");
                 return new RunResponse(UUID.randomUUID().toString(), run.getSessionId(), "Cancelled by user", Map.of(), List.of(), List.of(), RunStatus.CANCELLED, RunMetricsBuilder.noTelemetry());
            }
        } finally {
            span.end();
        }
    }

    /**
     * Records a HITL resume outcome counter for SRE alerting. Lazy-registers per
     * (outcome, tool) combination — Micrometer dedupes by name+tags.
     */
    private void recordResumeOutcome(String outcome, String tool) {
        Counter.builder(MetricConstants.HITL_RESUME_TOTAL)
                .tag("outcome", outcome)
                .tag("tool", tool == null ? "n/a" : tool)
                .description("AgentService.continueRun terminal-outcome counter; "
                        + "outcome ∈ {approved, rejected, error}; tool carries the resumed tool name when known.")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public String runInBackground(String agentId, String userInput, String sessionId) {
        return runInBackground(agentId, userInput, null, sessionId, null, null, false, null);
    }

    @Override
    public String runPlayground(String agentId, String userInput, String sessionId) {
        RunResponse response = run(agentId, userInput, sessionId);
        return response.content();
    }

    public AgentRun getRunStatus(String runId) {
        return runRepository.findById(runId).orElse(null);
    }

    /**
     * @summary Establishes a reactive connection to the underlying ChatClient, streaming LLM Token emissions as Server-Sent Events (SSE).
     * @logic
     * 1. Validates Agent configuration and routes to TeamOrchestrationEngine if it's a team.
     * 2. For single agents, delegates to AgentStreamManager for Flux construction.
     */
    @Override
    public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<org.springframework.ai.content.Media> media, String sessionId, String userId, String orgId, Boolean generateFollowups, RunOptions options) {
        AgentDefinition def = agentRegistry.findById(agentId, orgId);
        if (def == null) return Flux.error(new ResourceNotFoundException("Agent", agentId));

        try { checkConcurrencyLimit(agentId); } catch (BusinessValidationException ex) { return Flux.error(ex); }

        if (def.maintenanceMode()) {
            return Flux.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent is currently in maintenance mode"));
        }

        if (media != null && !media.isEmpty()) {
            String modelId = def.modelId();
            if (modelId != null) {
                java.util.Optional<ModelEntity> customModelOpt = modelService.getModelEntityById(modelId);
                if (customModelOpt.isPresent() && Boolean.FALSE.equals(customModelOpt.get().getSupportsVision())) {
                    return Flux.error(new BusinessValidationException("Model " + customModelOpt.get().getName() + " does not support vision (image analysis)."));
                }
            }
        }

        // §6 M-12 Phase 2: per-model rate-limit gate also fires on the streaming path so an
        // operator can't dodge the cap by switching from /run to /stream.
        try { modelRateLimitGuard.acquireOrThrow(def.modelId()); }
        catch (ai.operativus.agentmanager.core.exception.RateLimitExceededException ex) { return Flux.error(ex); }

        String effectiveSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : UUID.randomUUID().toString();

        Integer currentDepth = AgentContextHolder.getOrchestrationDepth();
        if (currentDepth >= 5) {
            return Flux.error(new BusinessValidationException("Max orchestration depth (5) reached. Aborting to prevent infinite loops."));
        }

        if (def.isTeam()) {
            return teamOrchestrationEngine.executeStream(
                    def, userInput, media, effectiveSessionId, userId, orgId,
                    generateFollowups, this, currentDepth, AgentContextHolder.getCurrentRunId());
        }

        ensureSessionExists(effectiveSessionId, agentId, userId, orgId);
        AgentRun runRecord = new AgentRun(agentId, effectiveSessionId, userInput, userId, orgId);
        runRecord.setParentRunId(AgentContextHolder.getCurrentRunId());
        runRecord.setStatus(RunStatus.RUNNING);
        runRepository.save(runRecord);

        // F22: Reactor operators in AgentStreamManager run on boundedElastic, which does NOT
        // inherit ScopedValues from this thread. Capture the bindings here on the caller-bound
        // thread so AgentStreamManager can re-bind them inside each operator's lambda; otherwise
        // the advisor chain (RAG, PII redaction, cultural memory, encryption gate) reads
        // AgentContextHolder.getOrgId() as null and tenant isolation silently breaks.
        RunTelemetryAccumulator streamTelemetry = new RunTelemetryAccumulator();
        streamTelemetry.setModelIfAbsent(def.modelId());
        AgentStreamManager.StreamScopeBindings bindings = new AgentStreamManager.StreamScopeBindings(
                orgId != null ? orgId : AgentContextHolder.getOrgId(),
                userId != null ? userId : AgentContextHolder.getUserId(),
                effectiveSessionId,
                agentId,
                def.name() != null ? def.name() : agentId,
                runRecord.getId(),
                currentDepth + 1,
                streamTelemetry,
                Collections.synchronizedList(new ArrayList<ToolCallDTO>()),
                new java.util.HashSet<String>(),
                new String[1],
                new ArrayList<>(def.knowledgeBaseIds() != null ? def.knowledgeBaseIds() : Collections.emptyList()),
                def.complianceTier() == ai.operativus.agentmanager.core.entity.ComplianceTier.TIER_2_STRICT
        );

        return agentStreamManager.stream(def, agentId, userInput, media, effectiveSessionId,
                userId, orgId, generateFollowups, options, runRecord, bindings);
    }

    public Flux<AgentStreamEvent> stream(String agentId, String userInput, String sessionId) {
        return stream(agentId, userInput, null, sessionId, null, null, false, null);
    }

    /**
     * @summary Ingests bootstrap knowledge URLs configured on the agent.
     * @logic Reads the "bootstrapKnowledgeUrls" key from the agent's configuration map.
     *        Each URL is ingested via the KnowledgeIngestionService.
     *        If no URLs are configured, throws an error.
     */
    @Override
    public void loadKnowledge(String agentId) {
        AgentDefinition def = agentRegistry.findById(agentId, AgentContextHolder.getOrgId());
        if (def == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        var config = def.configuration();
        if (config != null && config.containsKey("bootstrapKnowledgeUrls")) {
            Object urlsObj = config.get("bootstrapKnowledgeUrls");
            if (urlsObj instanceof java.util.List<?> urls) {
                for (Object url : urls) {
                    if (url instanceof String urlStr && !urlStr.isBlank()) {
                        log.info("Ingesting bootstrap knowledge URL for agent {}: {}", agentId, urlStr);
                        knowledgeIngestionService.ingestUrl(urlStr);
                    }
                }
                return;
            }
        }
        throw new IllegalArgumentException("No bootstrapKnowledgeUrls configured for agent: " + agentId);
    }

    private void ensureSessionExists(String sessionId, String agentId, String userId, String orgId) {
        String resolvedUser = userId != null ? userId : ai.operativus.agentmanager.control.config.SecurityContextUtils.resolveCurrentUserId();
        String resolvedOrg = orgId != null && !orgId.isBlank() ? orgId : ai.operativus.agentmanager.core.model.TenantConstants.DEFAULT_SYSTEM_ORG;
        // Cross-tenant + cross-agent session guard. When a caller supplies a sessionId that
        // ALREADY exists, the row's org_id MUST match the caller-bound org AND the row's
        // agent_id MUST match the path-param agent. Without this, an attacker could attach
        // their own run to another tenant's session row — the chat-memory advisor would then
        // pull the victim's conversation history into the attacker's prompt window
        // (cf. RunRequestValidationRuntimeTest cross-tenant + cross-agent pins). §79 RBAC
        // pattern — 404 not 403 to avoid leaking session-existence across tenants.
        //
        // Team-member exception: when this call is dispatched FROM inside a team
        // orchestrator (orchestrationDepth > 0), the agent_id-mismatch arm is
        // legitimate — the outer team's session row has agent_id=teamId, the
        // inner member's run is for agent_id=memberId. The orchestrationDepth
        // ScopedValue is bound by TeamOrchestrationEngine; it can't be set by
        // an external caller, so the security envelope around the orgId check
        // is preserved (cross-tenant is still rejected at line 1099).
        boolean dispatchedByTeamOrchestrator = AgentContextHolder.getOrchestrationDepth() > 0;
        // A workflow run legitimately drives many DIFFERENT agents under a single session
        // (one per step), so the agent_id pin below must not fire for workflow-dispatched
        // runs — exactly like the team-orchestrator case. getWorkflowRunId() is a ScopedValue
        // bound only by WorkflowService during execution/resume; external callers cannot set
        // it, so the cross-tenant org check (above) remains the security envelope.
        boolean dispatchedByWorkflow = AgentContextHolder.getWorkflowRunId() != null;
        sessionRepository.findById(sessionId).ifPresent(existing -> {
            String existingOrg = existing.getOrgId();
            if (existingOrg != null && !existingOrg.isBlank()
                    && !existingOrg.equals(resolvedOrg)) {
                throw new ResourceNotFoundException("Session", sessionId);
            }
            String existingAgent = existing.getAgentId();
            if (existingAgent != null && !existingAgent.equals(agentId)
                    && !dispatchedByTeamOrchestrator && !dispatchedByWorkflow) {
                throw new ResourceNotFoundException("Session", sessionId);
            }
        });
        sessionRepository.upsertEnsureExists(sessionId, agentId, resolvedUser, resolvedOrg);
    }

    private void checkConcurrencyLimit(String agentId) {
        checkConcurrencyLimit(agentId, null);
    }

    /**
     * @summary Enforces concurrency limits at two levels: per-agent FinOps cap and global system cap.
     * @logic First checks the agent-specific maxConcurrentExecutions (if configured on the AgentDefinition).
     *        Falls back to the global application-wide maxConcurrentCalls limit.
     *
     * <p>Background-path inner runs short-circuit: when {@code AgentContextHolder.preAllocatedRunId}
     * is bound, this is the inner {@code run()} invocation from {@code runInBackground}'s VT
     * lambda — and {@code RunExecutionManager.submit} has already saved the row as RUNNING
     * before this point. Counting it would (a) double-count the row's own RUNNING transition
     * and (b) reject the run with a confusing 200-OK-then-FAILED UX (the outer
     * {@code runInBackground} check has already passed). The outer check at line 499 is the
     * canonical gate for background submissions; this inner re-check would only inflate
     * rejections without enforcing anything new. Tier 1.4 audit finding F2.
     */
    private void checkConcurrencyLimit(String agentId, AgentDefinition def) {
        if (AgentContextHolder.preAllocatedRunId.isBound()) {
            return;
        }
        long runningCount = runRepository.countByAgentIdAndStatus(agentId, RunStatus.RUNNING);

        if (def != null && def.maxConcurrentExecutions() != null && def.maxConcurrentExecutions() > 0) {
            if (runningCount >= def.maxConcurrentExecutions()) {
                log.warn("Agent {} has reached its per-agent FinOps concurrency cap of {}", agentId, def.maxConcurrentExecutions());
                perAgentCapRejectionCounter.increment();
                throw new BusinessValidationException(
                    "Agent " + agentId + " has reached its configured concurrency limit of " + def.maxConcurrentExecutions() + ". Please wait for active runs to complete.");
            }
        }

        if (runningCount >= maxConcurrentCalls) {
            log.warn("Agent {} has reached the JVM-wide orchestration concurrency cap of {} (running={})",
                    agentId, maxConcurrentCalls, runningCount);
            globalCapRejectionCounter.increment();
            throw new BusinessValidationException("Agent " + agentId + " has reached maximum concurrent capacity of " + maxConcurrentCalls);
        }
    }
}
