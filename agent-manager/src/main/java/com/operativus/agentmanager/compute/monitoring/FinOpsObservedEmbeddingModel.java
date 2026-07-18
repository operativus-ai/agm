package com.operativus.agentmanager.compute.monitoring;

import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Domain Responsibility: Compile-time Decorator wrapping an {@link EmbeddingModel} to intercept
 * all embedding operations for FinOps telemetry and budget enforcement.
 *
 * For each {@code call(EmbeddingRequest)} invocation, this decorator:
 *   1. Delegates to the underlying provider EmbeddingModel.
 *   2. Extracts the prompt token count from {@code EmbeddingResponseMetadata.getUsage()}.
 *   3. Publishes a {@code gen_ai.client.token.usage} OTel metric (operation=embeddings).
 *   4. Translates token count to USD via {@link LiveValuationEngine} and accumulates into
 *      the current session budget via {@link GenAiMetricsAdvisor#accumulateEmbeddingCost},
 *      which will throw {@code FinOpsBudgetExhaustedException} if the ceiling is exceeded.
 *
 * Architecture:
 * - Pure Decorator pattern — no AOP, no Spring proxy magic.
 * - Constructor injection of all dependencies.
 * - All other {@code EmbeddingModel} methods delegate directly to the wrapped delegate,
 *   ensuring no provider-specific interface downcast compatibility issues.
 * - Virtual Thread safe — all paths are blocking I/O compatible.
 *
 * State: Stateless (delegates state to MeterRegistry and GenAiMetricsAdvisor).
 */
public class FinOpsObservedEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(FinOpsObservedEmbeddingModel.class);

    /** OTel GenAI Semantic Convention metric name (v1.39.0). */
    private static final String METRIC_TOKEN_USAGE    = "gen_ai.client.token.usage";
    private static final String METRIC_EMBED_COST_USD = "finops.embedding.cost.usd";

    private final EmbeddingModel delegate;
    private final MeterRegistry meterRegistry;
    private final LiveValuationEngine valuationEngine;
    private final GenAiMetricsAdvisor metricsAdvisor;

    /**
     * @param delegate        The provider-specific EmbeddingModel being decorated.
     * @param meterRegistry   Micrometer registry for OTel metric publication.
     * @param valuationEngine For USD conversion of embedding token counts.
     * @param metricsAdvisor  For session budget accumulation and ceiling enforcement.
     */
    public FinOpsObservedEmbeddingModel(
            EmbeddingModel delegate,
            MeterRegistry meterRegistry,
            LiveValuationEngine valuationEngine,
            GenAiMetricsAdvisor metricsAdvisor) {
        this.delegate        = delegate;
        this.meterRegistry   = meterRegistry;
        this.valuationEngine = valuationEngine;
        this.metricsAdvisor  = metricsAdvisor;
    }

    /**
     * @summary Intercept point: delegates embed call, then extracts Usage and publishes FinOps telemetry.
     * @logic
     *   1. Calls delegate.call(request) — fully provider-transparent.
     *   2. Extracts promptTokens from EmbeddingResponseMetadata.getUsage().
     *   3. Publishes gen_ai.client.token.usage with operation=embeddings.
     *   4. Accumulates cost into per-session budget tracker via GenAiMetricsAdvisor.
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);

        try {
            recordEmbeddingUsage(response);
        } catch (com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException ex) {
            // Re-throw budget exhaustion cleanly — this is a hard stop
            throw ex;
        } catch (Exception e) {
            log.warn("Failed to record embedding FinOps metrics. Non-fatal — continuing.", e);
        }

        return response;
    }

    /**
     * @summary Extracts token count from EmbeddingResponseMetadata, publishes OTel metric, and
     *          forwards the cost to the session budget accumulator.
     */
    private void recordEmbeddingUsage(EmbeddingResponse response) {
        if (response == null || response.getMetadata() == null) return;

        var metadata = response.getMetadata();
        if (metadata.getUsage() == null) return;

        var usage    = metadata.getUsage();
        long tokens  = usage.getPromptTokens();
        String model = metadata.getModel() != null ? metadata.getModel() : "unknown";

        if (tokens <= 0) return;

        // Publish OTel-compliant embedding token metric
        DistributionSummary.builder(METRIC_TOKEN_USAGE)
            .baseUnit("tokens")
            .tag("gen_ai.operation.name", "embeddings")
            .tag("gen_ai.request.model",  model)
            .tag("gen_ai.token.type",     "input")
            .description("Number of tokens consumed by embedding operations")
            .register(meterRegistry)
            .record(tokens);

        // Publish USD cost metric for embedding operations
        double costUsd = valuationEngine.calculateUsd(model, tokens, 0L);
        if (costUsd > 0) {
            DistributionSummary.builder(METRIC_EMBED_COST_USD)
                .baseUnit("usd")
                .tag("gen_ai.request.model", model)
                .description("Estimated USD cost of vector embedding operations")
                .register(meterRegistry)
                .record(costUsd);
        }

        // Accumulate into the per-session budget tracker (throws if ceiling exceeded)
        String sessionId = safeGetSessionId();
        metricsAdvisor.accumulateEmbeddingCost(sessionId, model, tokens);

        log.debug("Embedding FinOps: model={}, tokens={}, costUsd=${:.4f}, session={}", model, tokens, costUsd, sessionId);
    }

    private String safeGetSessionId() {
        try {
            String id = com.operativus.agentmanager.core.callback.AgentContextHolder.getSessionId();
            return id != null ? id : "unknown-session";
        } catch (Exception e) {
            return "unknown-session";
        }
    }

    // -------------------------------------------------------------------------
    // Delegation methods — all other EmbeddingModel interface methods pass through
    // to the underlying delegate unmodified, preserving provider-specific behaviour.
    // -------------------------------------------------------------------------

    @Override
    public float[] embed(Document document) {
        return delegate.embed(document);
    }

    @Override
    public float[] embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public java.util.List<float[]> embed(java.util.List<String> texts) {
        return delegate.embed(texts);
    }

    @Override
    public org.springframework.ai.embedding.EmbeddingResponse embedForResponse(java.util.List<String> texts) {
        return delegate.embedForResponse(texts);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
