package ai.operativus.agentmanager.compute.monitoring;

import ai.operativus.agentmanager.control.approval.HitlPauseHandler;
import ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException;
import ai.operativus.agentmanager.control.finops.model.FinOpsRecords.ChargebackTag;
import ai.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import ai.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventBus;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Domain Responsibility: Intercepts all ChatClient call AND stream completions to:
 *   1. Extract token usage metadata and publish OTel-compliant Micrometer metrics tagged with
 *      multi-tenant chargeback attributes (org_unit, project_code) following the
 *      {@code gen_ai.client.token.usage} semantic convention (OTel GenAI Metrics v1.39.0).
 *   2. Translate token counts to USD via the LiveValuationEngine.
 *   3. Enforce FinOps budget limits by throwing FinOpsBudgetExhaustedException when cumulative
 *      session spend exceeds the agent's authorized ceiling.
 *
 * Architecture Enforcement:
 * - The call path is fully blocking (virtual-thread-safe). The stream path uses only Flux
 *   lifecycle hooks: a per-chunk capture of the usage-bearing chunk, with all valuation /
 *   enforcement logic deferred to stream completion (usage is known only at end-of-stream).
 *   Enforcement on the stream path is therefore post-turn — it halts the next LLM call in the
 *   agentic loop rather than mid-token — but the HITL pause record and ceiling semantics match
 *   the call path. Without StreamAdvisor, streaming requests bypassed this advisor entirely:
 *   no token/cost metrics, no burn-rate push, and no budget enforcement.
 * - Strict Immutability: ChargebackTag and ModelValuationRate are records — never mutated.
 * - Constructor Injection for all dependencies.
 * - The advisor does NOT mutate the ChatClientRequest or ChatClientResponse.
 *
 * Ordering: Runs last (Integer.MAX_VALUE) after all other advisors have finalized the response,
 * so the token count is complete before enforcement is applied.
 *
 * State: Stateful (per-session cumulative USD tracker)
 */
@Component
public class GenAiMetricsAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GenAiMetricsAdvisor.class);

    /** OTel GenAI Semantic Convention metric name (v1.39.0). */
    private static final String METRIC_TOKEN_USAGE = "gen_ai.client.token.usage";
    private static final String METRIC_TOKEN_COST_USD = "gen_ai.client.token.cost.usd";

    /** OTel Semantic Convention tag keys. */
    private static final String TAG_OPERATION_NAME  = "gen_ai.operation.name";
    private static final String TAG_PROVIDER_NAME   = "gen_ai.provider.name";
    private static final String TAG_REQUEST_MODEL   = "gen_ai.request.model";
    private static final String TAG_RESPONSE_MODEL  = "gen_ai.response.model";
    private static final String TAG_TOKEN_TYPE      = "gen_ai.token.type";

    /** Chargeback dimension tag keys for multi-tenant financial attribution. */
    private static final String TAG_ORG_UNIT        = "finops.org_unit";
    private static final String TAG_PROJECT_CODE    = "finops.project_code";
    private static final String TAG_TEAM_ID         = "finops.team_id";
    private static final String TAG_SESSION_ID      = "finops.session_id";

    private final MeterRegistry meterRegistry;
    private final LiveValuationEngine valuationEngine;
    private final HitlPauseHandler hitlPauseHandler;
    private final AgentRunEventBus eventBus;
    private final BurnRateMonitorService burnRateMonitor;

    /**
     * Optional JVM-wide default session ceiling (USD). Applied only when
     * {@code AgentContextHolder.CONTEXT} is unbound — i.e. on the primary
     * {@code /api/agents/{id}/run} and {@code /stream} paths. Null → no enforcement on
     * those paths (operator opt-in). The OBO gateway path's per-request {@code
     * remainingBudget} always takes precedence over this property when both are present.
     *
     * <p>See {@code resolveBudgetCeiling()} for the precedence rule and SDD G2 (file:
     * {@code docs/plans/agm-finops-cost-enforcement-36.md}) for the rationale.
     */
    private final Double defaultSessionCeilingUsd;

    /** Pinned by {@code GenAiMetricsAdvisorStartupLogTest} — drift breaks operator runbooks. */
    static final String STARTUP_LOG_ENABLED_FORMAT =
            "FinOps session-ceiling enforcement: ENABLED at ${} per session (property: agentmanager.finops.default-session-ceiling-usd)";
    static final String STARTUP_LOG_DISABLED_MESSAGE =
            "FinOps session-ceiling enforcement: DISABLED (property agentmanager.finops.default-session-ceiling-usd is unset)";

    /**
     * Tracks cumulative USD spend per session for mid-flight FinOps enforcement.
     * Keyed by sessionId. AtomicReference allows compare-and-swap accumulation without locks.
     *
     * <p><strong>Why this exists alongside {@link BurnRateMonitorService}:</strong> the two
     *   trackers serve different purposes — this one is an unbounded per-session cumulative
     *   total used for hard ceiling enforcement (throws on breach), the burn-rate monitor is
     *   a 60-second rolling window used for anomaly alerting (logs + Micrometer gauges, never
     *   throws). Do not consolidate them — collapsing the two would lose the unbounded
     *   enforcement semantics. The wiring below is one-way: this advisor pushes spend events
     *   to the monitor; the monitor never reads back into this advisor.
     */
    private final ConcurrentHashMap<String, AtomicReference<Double>> sessionCumulativeUsd =
        new ConcurrentHashMap<>();

    public GenAiMetricsAdvisor(
            MeterRegistry meterRegistry,
            LiveValuationEngine valuationEngine,
            HitlPauseHandler hitlPauseHandler,
            AgentRunEventBus eventBus,
            BurnRateMonitorService burnRateMonitor,
            @org.springframework.beans.factory.annotation.Value("${agentmanager.finops.default-session-ceiling-usd:#{null}}")
                Double defaultSessionCeilingUsd) {
        this.meterRegistry = meterRegistry;
        this.valuationEngine = valuationEngine;
        this.hitlPauseHandler = hitlPauseHandler;
        this.eventBus = eventBus;
        this.burnRateMonitor = burnRateMonitor;
        this.defaultSessionCeilingUsd = defaultSessionCeilingUsd;
        if (defaultSessionCeilingUsd != null) {
            log.info(STARTUP_LOG_ENABLED_FORMAT, String.format("%.2f", defaultSessionCeilingUsd));
        } else {
            log.info(STARTUP_LOG_DISABLED_MESSAGE);
        }
    }

    @Override
    public String getName() {
        return "GenAiMetricsAdvisor";
    }

    /**
     * @summary Ensures this advisor runs last, after all other advisors have finalized the response.
     */
    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    /**
     * @summary Intercepts completed ChatClient calls to record metrics and enforce FinOps budgets.
     * @logic
     * 1. Passes request through the advisor chain unmodified (blocking, virtual-thread-safe).
     * 2. Extracts token usage from the materialized ChatResponse.
     * 3. Tags all telemetry with OTel semantic attributes and multi-tenant chargeback dimensions.
     * 4. Translates tokens to USD via the LiveValuationEngine.
     * 5. Accumulates session cumulative spend and checks against the agent's budget ceiling.
     * 6. If budget is exceeded: persists HITL pause record via HitlPauseHandler, then throws
     *    FinOpsBudgetExhaustedException to interrupt the agent loop cleanly.
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        try {
            ChargebackTag chargebackTag = resolveChargebackTag();
            recordTokenUsage(request, response, chargebackTag);
            enforceFinOpsBudget(request, response, chargebackTag);
        } catch (FinOpsBudgetExhaustedException ex) {
            hitlPauseHandler.pauseForBudgetExhaustion(ex);
            throw ex;
        } catch (Exception e) {
            log.warn("Failed to record GenAI token metrics. Non-fatal — continuing.", e);
        }

        return response;
    }

    /**
     * @summary Streaming counterpart of {@link #adviseCall}. Records token/cost metrics and enforces the
     *          FinOps budget for streamed turns, which previously bypassed this advisor entirely.
     * @logic
     * 1. Captures the last stream chunk carrying a positive usage block — providers report usage only on a
     *    late/terminal chunk, usually cumulatively.
     * 2. On stream completion (usage now final), runs the same record + enforce logic as the call path.
     *    Enforcement is post-turn by necessity: usage is unknown until the stream ends. On a ceiling breach
     *    the HITL pause record is written (parity with the call path) and the breach is stashed.
     * 3. A trailing deferred publisher re-emits the stashed breach as a terminal error so the agentic loop
     *    halts — concatWith keeps all content chunks intact and adds no spurious response chunk on success.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        AtomicReference<ChatClientResponse> lastWithUsage = new AtomicReference<>();
        AtomicReference<FinOpsBudgetExhaustedException> budgetBreach = new AtomicReference<>();

        return chain.nextStream(request)
                .doOnNext(chunk -> {
                    if (hasUsage(chunk)) lastWithUsage.set(chunk);
                })
                .doOnComplete(() -> {
                    try {
                        ChargebackTag chargebackTag = resolveChargebackTag();
                        recordTokenUsage(request, lastWithUsage.get(), chargebackTag);
                        enforceFinOpsBudget(request, lastWithUsage.get(), chargebackTag);
                    } catch (FinOpsBudgetExhaustedException ex) {
                        // Pause record mirrors the call path; the breach is surfaced as a terminal stream
                        // error by the deferred publisher below (doOnComplete cannot inject an error itself).
                        hitlPauseHandler.pauseForBudgetExhaustion(ex);
                        budgetBreach.set(ex);
                    } catch (Exception e) {
                        log.warn("Failed to record GenAI token metrics on the streaming path. Non-fatal — continuing.", e);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    FinOpsBudgetExhaustedException breach = budgetBreach.get();
                    return breach != null ? Flux.error(breach) : Flux.empty();
                }));
    }

    /** True when the response carries a token-usage block with at least one positive count — used to pick the
     *  streaming chunk holding the real (usually cumulative) usage. The positive check matters: Spring AI
     *  defaults missing metadata to a zero {@code EmptyUsage} (never null), and some providers emit a
     *  content-only chunk after the usage chunk, which a bare non-null check would let overwrite the totals. */
    private boolean hasUsage(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getMetadata() == null) {
            return false;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        if (usage == null) return false;
        return positive(usage.getPromptTokens()) || positive(usage.getCompletionTokens())
                || positive(usage.getTotalTokens());
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    /**
     * @summary Extracts token usage and publishes OTel-compliant metrics with chargeback tags.
     * @logic Reads tokenUsage from response metadata. Derives provider name from model ID.
     *        Records standard, cached, and reasoning token counts as distinct DistributionSummary
     *        entries (token_type: input / cached / reasoning / output), each tagged with OTel
     *        GenAI attributes and multi-tenant chargeback dimensions.
     *        Reasoning and cached token counts are interrogated defensively via reflection so the
     *        advisor degrades gracefully on Spring AI builds that don't expose these fields yet.
     */
    private void recordTokenUsage(ChatClientRequest request, ChatClientResponse response,
                                   ChargebackTag chargebackTag) {
        if (response == null || response.chatResponse() == null) return;

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse.getMetadata() == null || chatResponse.getMetadata().getUsage() == null) return;

        var usage = chatResponse.getMetadata().getUsage();
        String responseModel = chatResponse.getMetadata().getModel() != null
                ? chatResponse.getMetadata().getModel()
                : "unknown";

        String requestModel  = resolveRequestModel(request);
        String providerName  = deriveProviderName(responseModel);

        long inputTokens    = usage.getPromptTokens();
        long outputTokens   = usage.getCompletionTokens();

        // Attempt to read granular details from provider-specific Usage implementations.
        // Falls back to null when the model / Spring AI build doesn't support these fields.
        Long reasoningTokens = safeGetReasoningTokens(usage);
        Long cachedTokens    = safeGetCachedTokens(usage);

        // Standard input tokens — publish with cached sub-metric if available
        if (inputTokens > 0) {
            recordTokenMetric(providerName, requestModel, responseModel, "input", inputTokens, chargebackTag);
        }
        if (cachedTokens != null && cachedTokens > 0) {
            recordTokenMetric(providerName, requestModel, responseModel, "cached", cachedTokens, chargebackTag);
        }
        if (reasoningTokens != null && reasoningTokens > 0) {
            recordTokenMetric(providerName, requestModel, responseModel, "reasoning", reasoningTokens, chargebackTag);
        }
        if (outputTokens > 0) {
            recordTokenMetric(providerName, requestModel, responseModel, "output", outputTokens, chargebackTag);
        }

        log.debug("GenAI Metrics recorded — model={}, input={}tok, cached={}tok, reasoning={}tok, output={}tok, org={}, project={}",
            responseModel, inputTokens,
            cachedTokens != null ? cachedTokens : 0L,
            reasoningTokens != null ? reasoningTokens : 0L,
            outputTokens, chargebackTag.orgUnit(), chargebackTag.projectCode());
    }

    /**
     * @summary Checks whether the cumulative session USD spend has exceeded the agent's budget ceiling.
     * @logic Resolves the budget ceiling from the bound AgentContext (control.security.AgentContextHolder).
     *        Translates this call's tokens to USD via LiveValuationEngine and accumulates into the
     *        per-session concurrent tracker. If cumulative spend > remaining budget ceiling, throws
     *        FinOpsBudgetExhaustedException to interrupt the stream.
     */
    private void enforceFinOpsBudget(ChatClientRequest request, ChatClientResponse response,
                                      ChargebackTag chargebackTag) {
        if (response == null || response.chatResponse() == null) return;
        if (response.chatResponse().getMetadata() == null
                || response.chatResponse().getMetadata().getUsage() == null) return;

        var usage = response.chatResponse().getMetadata().getUsage();
        String modelId = response.chatResponse().getMetadata().getModel() != null
                ? response.chatResponse().getMetadata().getModel()
                : resolveRequestModel(request);

        long inputTokens    = usage.getPromptTokens();
        long outputTokens   = usage.getCompletionTokens();
        long reasoningTokens = safeGetReasoningTokensLong(usage);
        long cachedTokens    = safeGetCachedTokensLong(usage);

        double callUsd = valuationEngine.calculateUsdBreakdown(modelId, inputTokens, outputTokens, reasoningTokens, cachedTokens);

        // Feed run-scoped cost telemetry so AgentRunFinalizer persists AgentRun.totalCostUsd and the
        // end-of-stream usage summary can report spend. addCostUsd had ZERO callers before this — the
        // run row's totalCostUsd was silently always null on every path despite the column existing.
        RunTelemetryAccumulator runTelemetry = AgentContextHolder.getTelemetry();
        if (runTelemetry != null) runTelemetry.addCostUsd(callUsd);

        // Publish USD cost metric unconditionally for chargeback and telemetry mapping
        DistributionSummary.builder(METRIC_TOKEN_COST_USD)
            .baseUnit("usd")
            .tag(TAG_PROVIDER_NAME, deriveProviderName(modelId))
            .tag(TAG_REQUEST_MODEL, modelId)
            .tag(TAG_ORG_UNIT,      chargebackTag.orgUnit())
            .tag(TAG_PROJECT_CODE,  chargebackTag.projectCode())
            .tag(TAG_TEAM_ID,       chargebackTag.teamId())
            .tag(TAG_SESSION_ID,    chargebackTag.sessionId())
            .description("Estimated USD cost per inference call")
            .register(meterRegistry)
            .record(callUsd);

        String sessionId = chargebackTag.sessionId() != null ? chargebackTag.sessionId() : "UNKNOWN";

        // Push spend to the burn-rate monitor BEFORE the ceiling check so anomaly alerts
        // fire even on the call that triggers hard rejection. recordSpend is no-op for
        // non-positive amounts, so a zero callUsd safely falls through.
        burnRateMonitor.recordSpend(sessionId, resolveAgentId(), callUsd);

        Double budgetCeiling = resolveBudgetCeiling();
        if (budgetCeiling == null) return; // No budget constraint configured
        AtomicReference<Double> cumulativeRef = sessionCumulativeUsd
            .computeIfAbsent(sessionId, k -> new AtomicReference<>(0.0));

        // Accumulate using updateAndGet which handles CAS and autoboxing internally
        double updated = cumulativeRef.updateAndGet(curr -> curr + callUsd);

        log.debug("FinOps budget check — session={}, callUsd=${:.4f}, cumulativeUsd=${:.4f}, ceiling=${:.4f}",
            sessionId, callUsd, updated, budgetCeiling);

        if (updated > budgetCeiling) {
            String agentId  = resolveAgentId();
            String runId    = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();

            log.warn("FinOps ceiling breached — session={}, agent={}, cumulative=${:.4f} > ceiling=${:.4f}",
                sessionId, agentId, updated, budgetCeiling);

            publishBudgetExceeded(sessionId, modelId, updated, budgetCeiling, "inference");
            throw new FinOpsBudgetExhaustedException(sessionId, agentId, runId, modelId, updated, budgetCeiling);
        }
    }

    /**
     * @summary Publishes a single token usage metric entry with OTel GenAI + chargeback tags.
     */
    private void recordTokenMetric(String providerName, String requestModel, String responseModel,
                                    String tokenType, long tokenCount, ChargebackTag chargebackTag) {
        DistributionSummary.builder(METRIC_TOKEN_USAGE)
            .baseUnit("tokens")
            .tag(TAG_OPERATION_NAME,  "chat")
            .tag(TAG_PROVIDER_NAME,   providerName)
            .tag(TAG_REQUEST_MODEL,   requestModel)
            .tag(TAG_RESPONSE_MODEL,  responseModel)
            .tag(TAG_TOKEN_TYPE,      tokenType)
            .tag(TAG_ORG_UNIT,        chargebackTag.orgUnit())
            .tag(TAG_PROJECT_CODE,    chargebackTag.projectCode())
            .tag(TAG_TEAM_ID,         chargebackTag.teamId())
            .tag(TAG_SESSION_ID,      chargebackTag.sessionId())
            .description("Measures number of input and output tokens used")
            .register(meterRegistry)
            .record(tokenCount);
    }

    /**
     * @summary Resolves the multi-tenant chargeback tag from the active agent execution context.
     * @logic Reads orgId, sessionId, and teamId from core.callback.AgentContextHolder ScopedValues.
     *        Falls back to neutral defaults when context is unbound (e.g., test or non-agent calls).
     */
    private ChargebackTag resolveChargebackTag() {
        var ctx = ai.operativus.agentmanager.core.callback.AgentContextHolder.class;
        String orgUnit    = safeGet(() -> ai.operativus.agentmanager.core.callback.AgentContextHolder.getOrgId(), "unknown-org");
        String sessionId  = safeGet(() -> ai.operativus.agentmanager.core.callback.AgentContextHolder.getSessionId(), "unknown-session");

        // teamId is accessible via control.security.AgentContextHolder when bound
        String teamId = "unknown-team";
        try {
            var agentCtx = ai.operativus.agentmanager.control.security.AgentContextHolder.getContext();
            if (agentCtx.teamId() != null) teamId = agentCtx.teamId();
        } catch (Exception ignored) {}

        // projectCode can be derived from orgId or extended in future via manifest metadata
        String projectCode = orgUnit.equals("unknown-org") ? "unattributed" : orgUnit;

        return new ChargebackTag(orgUnit, projectCode, teamId, sessionId);
    }

    /**
     * @summary Resolves the budget ceiling for this request, with two-tier precedence.
     * @logic
     *   1. If {@code AgentContextHolder.CONTEXT} is bound (OBO gateway path) AND
     *      {@code remainingBudget()} is non-null, that value is authoritative — it carries
     *      the bounded-autonomy contract from the gateway and must always win.
     *   2. Otherwise (primary {@code /api/agents/{id}/run} path, where CONTEXT is unbound),
     *      fall back to {@code defaultSessionCeilingUsd}. Null → no enforcement on this
     *      path (operator opt-in default).
     *
     *   Drift in this precedence rule is a safety regression: if step 2 ran first or the
     *   ordering swapped, an OBO gateway request with a tighter per-request budget than the
     *   property would silently lose its tighter ceiling. Pinned by
     *   {@code GenAiMetricsAdvisorPropertyCeilingTest}.
     */
    private Double resolveBudgetCeiling() {
        try {
            var agentCtx = ai.operativus.agentmanager.control.security.AgentContextHolder.getContext();
            Double requestScoped = agentCtx.remainingBudget();
            if (requestScoped != null) return requestScoped;
        } catch (Exception ignored) {
            // CONTEXT unbound — fall through to property default below.
        }
        return defaultSessionCeilingUsd;
    }

    /**
     * @summary Resolves the current agent identifier from the execution context.
     * @logic Tries the HTTP-side manifest first; falls back to the scoped agentId
     *        bound on the agent virtual thread. Never returns a runId — that would
     *        silently mislabel FinOps anomaly entries (Bug #44).
     */
    private String resolveAgentId() {
        try {
            var agentCtx = ai.operativus.agentmanager.control.security.AgentContextHolder.getContext();
            if (agentCtx.agentManifest() != null && agentCtx.agentManifest().agentId() != null) {
                return agentCtx.agentManifest().agentId();
            }
        } catch (Exception ignored) {}
        return safeGet(() -> ai.operativus.agentmanager.core.callback.AgentContextHolder.getAgentId(), "UNKNOWN_AGENT");
    }

    /**
     * @summary Extracts the requested model name from the ChatClientRequest prompt options.
     */
    private String resolveRequestModel(ChatClientRequest request) {
        if (request != null && request.prompt() != null && request.prompt().getOptions() != null) {
            String model = request.prompt().getOptions().getModel();
            if (model != null && !model.isBlank()) return model;
        }
        return "unknown";
    }

    /**
     * @summary Derives the AI provider name from the response model identifier string.
     * @logic Matches known model ID prefixes to canonical OTel provider names.
     */
    private String deriveProviderName(String modelId) {
        if (modelId == null) return "unknown";
        String lower = modelId.toLowerCase();
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")) {
            return "openai";
        } else if (lower.startsWith("claude")) {
            return "anthropic";
        } else if (lower.startsWith("gemini") || lower.startsWith("models/gemini")) {
            return "google";
        } else if (lower.startsWith("mistral") || lower.startsWith("codestral")) {
            return "mistral";
        } else if (lower.startsWith("llama") || lower.startsWith("deepseek")) {
            return "meta";
        }
        return "unknown";
    }

    /**
     * @summary Null-safe supplier wrapper returning the default value on any exception.
     */
    private <T> T safeGet(java.util.function.Supplier<T> supplier, T defaultValue) {
        try {
            T value = supplier.get();
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * @summary Public hook for embedding-layer cost accumulation into the same per-session budget tracker.
     * @logic Called by {@code FinOpsObservedEmbeddingModel} after each {@code embed()} call so that
     *        embedding token costs are included in the session cumulative spend and enforced against
     *        the same budget ceiling as LLM inference calls.
     *
     * @param sessionId  Current agent session identifier.
     * @param modelId    Embedding model identifier (for USD calculation via LiveValuationEngine).
     * @param tokenCount Total tokens consumed by the embed call (getPromptTokens from EmbeddingResponseMetadata).
     */
    public void accumulateEmbeddingCost(String sessionId, String modelId, long tokenCount) {
        if (tokenCount <= 0 || sessionId == null) return;

        // Embeddings are input-only — output token count = 0
        double costUsd = valuationEngine.calculateUsd(modelId, tokenCount, 0L);
        if (costUsd <= 0) return;

        // Embedding spend is part of the run's cost — fold it into the same run-scoped accumulator
        // as inference so AgentRun.totalCostUsd and the usage summary include RAG/embedding cost.
        RunTelemetryAccumulator runTelemetry = AgentContextHolder.getTelemetry();
        if (runTelemetry != null) runTelemetry.addCostUsd(costUsd);

        String resolvedSession = sessionId.isBlank() ? "UNKNOWN" : sessionId;

        // Push embedding spend to the burn-rate monitor on the same path as inference spend
        // so the rolling-window alerting captures total per-session cost, not just LLM cost.
        burnRateMonitor.recordSpend(resolvedSession, resolveAgentId(), costUsd);

        AtomicReference<Double> cumulativeRef = sessionCumulativeUsd
            .computeIfAbsent(resolvedSession, k -> new AtomicReference<>(0.0));

        double updated = cumulativeRef.updateAndGet(curr -> curr + costUsd);

        Double budgetCeiling = resolveBudgetCeiling();
        if (budgetCeiling != null && updated > budgetCeiling) {
            String agentId = resolveAgentId();
            String runId   = ai.operativus.agentmanager.core.callback.AgentContextHolder.getCurrentRunId();
            log.warn("FinOps ceiling breached by embedding cost — session={}, cumulative=${:.4f} > ceiling=${:.4f}",
                resolvedSession, updated, budgetCeiling);
            publishBudgetExceeded(resolvedSession, modelId, updated, budgetCeiling, "embedding");
            throw new FinOpsBudgetExhaustedException(resolvedSession, agentId, runId, modelId, updated, budgetCeiling);
        }
    }

    /**
     * @summary Publishes a BUDGET_EXCEEDED timeline event so the halt is distinguishable from RUN_FAILED.
     * @logic Fires before the {@link FinOpsBudgetExhaustedException} propagates. When the event bus is
     *        unavailable or the current run ID is unbound (e.g. tests, non-agent callers), emission is
     *        a no-op. Any bus exception is swallowed — budget enforcement must never be blocked by a
     *        telemetry failure (R-18 isolation).
     */
    private void publishBudgetExceeded(String sessionId, String modelId, double cumulativeUsd,
                                       double budgetCeiling, String costKind) {
        if (eventBus == null) return;
        String runId = AgentContextHolder.getCurrentRunId();
        if (runId == null) return;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("modelId", modelId);
            payload.put("cumulativeUsd", cumulativeUsd);
            payload.put("budgetCeilingUsd", budgetCeiling);
            payload.put("costKind", costKind);
            AgentRunEvent event = new AgentRunEvent(
                    AgentRunEventType.BUDGET_EXCEEDED,
                    runId,
                    AgentContextHolder.getAgentId(),
                    null,
                    AgentContextHolder.getSessionId(),
                    AgentContextHolder.getOrgId(),
                    AgentContextHolder.getOrchestrationDepth(),
                    payload,
                    Instant.now());
            eventBus.publish(event);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish BUDGET_EXCEEDED event runId={}", runId, ex);
        }
    }

    /**
     * @summary Evicts the cumulative USD tracker entry for the given session to prevent unbounded map growth (R-9).
     * @logic Callers should invoke this when a session terminates (session close, workflow completion,
     *        or any point after which no further LLM calls will be made under that sessionId). Calling
     *        it between turns of a live multi-turn chat session would reset mid-flight budget enforcement
     *        and is intentionally not wired from AgentService.run(); see logging-plan Pre-4 notes.
     *
     * @param sessionId The session identifier whose cumulative USD tracker should be evicted. No-op if null or unknown.
     */
    public void clearSession(String sessionId) {
        if (sessionId == null) return;
        sessionCumulativeUsd.remove(sessionId);
    }

    /**
     * @summary Defensively reads reasoning token count from provider-specific Usage via reflection.
     * @logic Calls {@code getGenerationDetails().getReasoningTokens()} on the usage object.
     *        Returns null if the method doesn't exist or returns null — caller decides fallback.
     */
    private Long safeGetReasoningTokens(Object usage) {
        try {
            java.lang.reflect.Method getDetails = usage.getClass().getMethod("getGenerationDetails");
            Object details = getDetails.invoke(usage);
            if (details == null) return null;
            java.lang.reflect.Method getTokens = details.getClass().getMethod("getReasoningTokens");
            return (Long) getTokens.invoke(details);
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience variant returning 0L instead of null (for arithmetic paths). */
    private long safeGetReasoningTokensLong(Object usage) {
        Long v = safeGetReasoningTokens(usage);
        return v != null ? v : 0L;
    }

    /**
     * @summary Defensively reads cached prompt token count from provider-specific Usage via reflection.
     * @logic Calls {@code getPromptDetails().getCachedTokens()} on the usage object.
     *        Returns null if the method doesn't exist or returns null — caller decides fallback.
     */
    private Long safeGetCachedTokens(Object usage) {
        try {
            java.lang.reflect.Method getDetails = usage.getClass().getMethod("getPromptDetails");
            Object details = getDetails.invoke(usage);
            if (details == null) return null;
            java.lang.reflect.Method getTokens = details.getClass().getMethod("getCachedTokens");
            return (Long) getTokens.invoke(details);
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience variant returning 0L instead of null (for arithmetic paths). */
    private long safeGetCachedTokensLong(Object usage) {
        Long v = safeGetCachedTokens(usage);
        return v != null ? v : 0L;
    }
}
