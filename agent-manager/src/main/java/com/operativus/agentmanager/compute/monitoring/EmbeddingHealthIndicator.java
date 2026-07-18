package com.operativus.agentmanager.compute.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: Surfaces the operational state of the {@code @Primary EmbeddingModel} that
 *     backs the pgvector store (RAG + agentic memory), so a silently-broken setup is visible instead
 *     of returning noise. Probes the elected model ONCE at startup and classifies it:
 *     <ul>
 *       <li><b>DISABLED_NOOP</b> — the model returns zero-vectors (the {@code NoOpEmbeddingModel}
 *           fallback was elected because no real embedding provider is configured) → semantic search
 *           is non-functional (every query matches nothing meaningfully).</li>
 *       <li><b>DIMENSION_MISMATCH</b> — the model emits a vector whose length ≠ the configured
 *           pgvector dimension → writes/queries against the store would fail or corrupt similarity.</li>
 *       <li><b>OPERATIONAL</b> — a real, dimension-matched model is producing non-zero vectors.</li>
 *       <li><b>PROBE_ERROR</b> — the probe threw (e.g. missing/invalid provider key).</li>
 *     </ul>
 *     The verdict is exposed via {@code /actuator/health} details, the
 *     {@code agm.embedding.search.operational} / {@code agm.embedding.dimensions} gauges, and a loud
 *     startup log (WARN for disabled, ERROR for mismatch).
 *
 *     <p><b>Always reports UP</b> (with details). RAG is an OPTIONAL capability — a deployment with
 *     no embedding model is a valid configuration — so this contributor must never flip the aggregate
 *     health to non-200 and break the container/load-balancer healthcheck (the prod Dockerfile probes
 *     {@code /actuator/health}). The real state lives in the {@code searchOperational} / {@code state}
 *     details and the gauges, not in the health status.
 *
 *     <p>The probe uses {@code EmbeddingModel.embed(String)}, which on the {@code @Primary} bean
 *     ({@code FinOpsObservedEmbeddingModel}) delegates straight to the underlying model — the FinOps
 *     metering/budget enforcement lives only on {@code call(EmbeddingRequest)} — so a context-free
 *     startup probe has no metering side-effects.
 * State: Stateful (caches the last probe result; Spring singleton).
 */
@Component
public class EmbeddingHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingHealthIndicator.class);
    private static final float ZERO_EPSILON = 1e-9f;
    private static final String PROBE_TEXT = "agm embedding healthcheck probe";

    public enum State { UNKNOWN, OPERATIONAL, DISABLED_NOOP, DIMENSION_MISMATCH, PROBE_ERROR }

    private final EmbeddingModel embeddingModel;
    private final int storeDimensions;

    private volatile State state = State.UNKNOWN;
    private volatile int modelDimensions = -1;
    private volatile String modelClass = "unknown";
    private volatile String detail = "not yet probed";

    public EmbeddingHealthIndicator(EmbeddingModel embeddingModel,
                                    MeterRegistry meterRegistry,
                                    @Value("${spring.ai.vectorstore.pgvector.dimension:768}") int storeDimensions) {
        this.embeddingModel = embeddingModel;
        this.storeDimensions = storeDimensions;
        Gauge.builder("agm.embedding.search.operational", this, h -> h.state == State.OPERATIONAL ? 1.0 : 0.0)
                .description("1 when a real, dimension-matched embedding model backs semantic search; 0 otherwise")
                .register(meterRegistry);
        Gauge.builder("agm.embedding.dimensions", this, h -> h.modelDimensions)
                .description("Embedding vector dimension produced by the elected model (-1 = unknown/unprobed)")
                .register(meterRegistry);
    }

    /** One-time probe after the context is up (avoids embedding network calls during bean wiring). */
    @EventListener(ApplicationReadyEvent.class)
    public void probeOnce() {
        modelClass = embeddingModel.getClass().getSimpleName();
        try {
            float[] v = embeddingModel.embed(PROBE_TEXT);
            modelDimensions = (v == null) ? 0 : v.length;
            boolean hasSignal = v != null && hasNonZero(v);
            if (!hasSignal) {
                state = State.DISABLED_NOOP;
                detail = "Embedding model returns zero-vectors (NoOp fallback) — no real embedding provider configured.";
                log.warn("Semantic search DISABLED: {} Set DEFAULT_MODEL_EMBEDDING to a real {}-dim embedding model "
                         + "(e.g. Google text-embedding-004) so RAG/memory search returns real results.",
                        detail, storeDimensions);
            } else if (modelDimensions != storeDimensions) {
                state = State.DIMENSION_MISMATCH;
                detail = "Embedding model dimension " + modelDimensions + " != pgvector store dimension " + storeDimensions + ".";
                log.error("Semantic search MISCONFIGURED: {} RAG writes/queries will fail or corrupt similarity. "
                          + "Fix DEFAULT_MODEL_EMBEDDING or spring.ai.vectorstore.pgvector.dimension.", detail);
            } else {
                state = State.OPERATIONAL;
                detail = "ok";
                log.info("Semantic search operational: embedding model producing {}-dim vectors (matches store).",
                        modelDimensions);
            }
        } catch (Exception e) {
            state = State.PROBE_ERROR;
            modelDimensions = -1;
            detail = "Embedding probe failed: " + e.getClass().getSimpleName()
                     + (e.getMessage() != null ? ": " + e.getMessage() : "");
            log.warn("Semantic search UNVERIFIED: embedding probe threw — likely a missing/invalid provider key. {}", detail);
        }
    }

    @Override
    public Health health() {
        // Always UP: RAG is optional; never break the container/LB healthcheck. State lives in details.
        return Health.up()
                .withDetail("searchOperational", state == State.OPERATIONAL)
                .withDetail("state", state.name())
                .withDetail("model", modelClass)
                .withDetail("modelDimensions", modelDimensions)
                .withDetail("storeDimensions", storeDimensions)
                .withDetail("detail", detail)
                .build();
    }

    private static boolean hasNonZero(float[] v) {
        for (float f : v) {
            if (Math.abs(f) > ZERO_EPSILON) return true;
        }
        return false;
    }

    // ── exposed for tests ────────────────────────────────────────────────────
    State currentState() { return state; }
    int currentModelDimensions() { return modelDimensions; }
}
