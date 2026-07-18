package com.operativus.agentmanager.compute.security;

import com.operativus.agentmanager.core.entity.ModelEntity;
import com.operativus.agentmanager.core.exception.RateLimitExceededException;
import com.operativus.agentmanager.core.registry.ModelOperations;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Domain Responsibility: §6 M-12 — enforces an optional per-model RPM ceiling at the LLM
 * call site. When a model row has a non-null {@code rate_limit_rpm}, every LLM invocation
 * routed through that model must acquire a permit from a Resilience4j RateLimiter keyed by
 * {@code "model:" + id}. Exceeding the ceiling throws {@link RateLimitExceededException},
 * which the global exception handler translates to a 429 with {@code Retry-After: 60}.
 *
 * <p>This is a complement to the global per-user {@code RateLimitingFilter} at the servlet
 * edge — that filter caps a user's total request rate, while this guard caps a single
 * model's invocation rate across all users. Useful when a downstream provider's quota is
 * tighter than AGM's user-level cap (partner-shared keys, free-tier endpoints).
 *
 * <p>State: Stateful (caches Resilience4j RateLimiter instances + last-known configured RPM
 * per model so configuration changes are picked up without an app restart).
 */
@Component
public class ModelRateLimitGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelRateLimitGuard.class);
    private static final String KEY_PREFIX = "model:";
    private static final Duration REFRESH_PERIOD = Duration.ofMinutes(1);

    private final ModelOperations modelOperations;
    private final RateLimiterRegistry registry;
    private final RedisModelRateLimiter redisLimiter;
    private final boolean distributedEnabled;

    /** Distributed-path observability. Three outcomes on the SAME meter name with an
     *  {@code outcome} tag so Grafana/PromQL can express "fallback ratio = fallback / (acquired
     *  + rejected + fallback)" with a single query. Local-only path is unmetered today —
     *  Resilience4j ships its own micrometer-registry-* binder when wired. */
    private final Counter distributedAcquired;
    private final Counter distributedRejected;
    private final Counter distributedFallback;

    /** Tracks the RPM the cached limiter was built with. Mismatch → rebuild on next access. */
    private final ConcurrentMap<String, Integer> appliedRpm = new ConcurrentHashMap<>();

    /** Once-per-JVM idempotency for the multi-instance discoverability boot log. JVM-static
     *  rather than instance-static so multiple Spring contexts in the same Surefire JVM
     *  (every {@code @SpringBootTest} class spawns one) only emit the line once. */
    private static final AtomicBoolean STARTUP_LOG_EMITTED = new AtomicBoolean(false);

    /** Canonical operator-facing wording for the multi-instance discoverability boot log
     *  when distributed mode is OFF (today's default). Pinned by
     *  {@code ModelRateLimitGuardStartupLogTest}; drift here will fail that test. */
    static final String STARTUP_LOG_MESSAGE =
            "ModelRateLimitGuard: per-model rate limits are enforced PER JVM PROCESS. "
                    + "In multi-instance deployments the effective ceiling is N replicas × configured rpm. "
                    + "If a global ceiling is required, switch to a Redis-backed RateLimiterRegistry.";

    /** Canonical wording emitted at WARN when the Redis-distributed acquire path
     *  fails and the guard falls back to the local Resilience4j gate.
     *  Pinned by {@code ModelRateLimitGuardDistributedRuntimeTest}; drift here will fail
     *  that test's container-paused case. SLF4J format string with one parameter
     *  ({@code modelId}); the throwable is passed as the trailing arg. */
    public static final String REDIS_FALLBACK_LOG_MESSAGE =
            "Redis rate-limit unreachable; falling back to local Resilience4j gate. modelId={}";

    /** Canonical wording for the boot log when distributed mode is ON.
     *  Pinned by {@code ModelRateLimitGuardStartupLogTest} (distributed-enabled case). */
    static final String STARTUP_LOG_MESSAGE_DISTRIBUTED =
            "ModelRateLimitGuard: distributed mode ENABLED (Redis-backed). "
                    + "Effective ceiling is the configured rpm globally across all replicas. "
                    + "Falls back to per-JVM Resilience4j on Redis unreachability.";

    @Autowired
    public ModelRateLimitGuard(
            ModelOperations modelOperations,
            RateLimiterRegistry registry,
            RedisModelRateLimiter redisLimiter,
            MeterRegistry meterRegistry,
            @Value("${agentmanager.rate-limit.distributed-model.enabled:false}") boolean distributedEnabled) {
        this.modelOperations = modelOperations;
        this.registry = registry;
        this.redisLimiter = redisLimiter;
        this.distributedEnabled = distributedEnabled;
        MeterRegistry registryToUse = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.distributedAcquired = Counter.builder(METRIC_NAME)
                .tag("outcome", "acquired").register(registryToUse);
        this.distributedRejected = Counter.builder(METRIC_NAME)
                .tag("outcome", "rejected").register(registryToUse);
        this.distributedFallback = Counter.builder(METRIC_NAME)
                .tag("outcome", "fallback").register(registryToUse);
    }

    /** Public meter name pinned for downstream dashboards. Drift breaks alerts. */
    public static final String METRIC_NAME = "agm.rate_limit.distributed";

    /** Test seam: existing unit tests cover only the local Resilience4j path; this overload
     *  keeps them working without forcing them to construct a Redis dependency they never
     *  exercise. With {@code distributedEnabled=false} the {@code redisLimiter} field is
     *  unused, so passing {@code null} is safe. */
    ModelRateLimitGuard(ModelOperations modelOperations, RateLimiterRegistry registry) {
        this(modelOperations, registry, null, null, false);
    }

    /** Test seam: distributed-path tests need to drive the full constructor with a real
     *  {@link RedisModelRateLimiter} mock + a {@link SimpleMeterRegistry} they can inspect.
     *  Public so cross-package integration tests in {@code integration/security/} can
     *  construct two instances simulating two replicas without joining this package. */
    public ModelRateLimitGuard(ModelOperations modelOperations, RateLimiterRegistry registry,
                               RedisModelRateLimiter redisLimiter, boolean distributedEnabled) {
        this(modelOperations, registry, redisLimiter, null, distributedEnabled);
    }

    /**
     * @summary §6 M-12 multi-instance discoverability log (Anti-pattern A5 mitigation).
     * @logic Fires once per JVM lifetime via {@link AtomicBoolean#compareAndSet}, NOT once per
     *     Spring context. {@code @EventListener(ApplicationReadyEvent.class)} guarantees the
     *     log fires AFTER the context is fully started, after any test-time bean overrides
     *     have settled. The log is INFO so operators with tight log filters can suppress;
     *     the goal is discoverability of the per-JVM scope, not interruption.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void emitMultiInstanceDiscoverabilityLog() {
        if (STARTUP_LOG_EMITTED.compareAndSet(false, true)) {
            log.info(distributedEnabled ? STARTUP_LOG_MESSAGE_DISTRIBUTED : STARTUP_LOG_MESSAGE);
        }
    }

    /** Test seam: lets a test reset the idempotency flag so multiple Spring contexts in the
     *  same JVM can each verify the log fires (only one ever WILL — this is the test for that). */
    static void resetStartupLogForTest() {
        STARTUP_LOG_EMITTED.set(false);
    }

    /**
     * @summary Acquire a per-model permit before an LLM call.
     * @logic No-op if {@code modelId} is null (synthetic / default-model path) or if the
     *     model has no per-model override configured. Otherwise tries to acquire a permit
     *     from the limiter; on rejection throws {@link RateLimitExceededException}.
     */
    public void acquireOrThrow(String modelId) {
        if (modelId == null) return;
        Integer rpm = lookupRpm(modelId);
        if (rpm == null || rpm <= 0) return;

        if (distributedEnabled) {
            try {
                if (!redisLimiter.tryAcquire(modelId, rpm)) {
                    distributedRejected.increment();
                    rejectOverCeiling(modelId, rpm);
                }
                distributedAcquired.increment();
                return;
            } catch (DataAccessException e) {
                distributedFallback.increment();
                log.warn(REDIS_FALLBACK_LOG_MESSAGE, modelId, e);
                // fall through to the local path
            }
        }

        RateLimiter limiter = limiterFor(modelId, rpm);
        if (!limiter.acquirePermission()) {
            rejectOverCeiling(modelId, rpm);
        }
    }

    private void rejectOverCeiling(String modelId, int rpm) {
        log.warn("Per-model rate limit exceeded for model {} (configured {} rpm)", modelId, rpm);
        throw new RateLimitExceededException(
                "Rate limit exceeded for this model. Configured ceiling: "
                        + rpm + " requests/minute. Try again shortly.");
    }

    /** Test seam: surfaces the current registry size so tests can pin lazy creation behavior. */
    int registeredLimiterCount() {
        return registry.getAllRateLimiters().size();
    }

    /**
     * @summary Drop the cached limiter + applied-rpm bookkeeping for {@code modelId}.
     * @logic Called by {@link com.operativus.agentmanager.control.service.ModelService#clearRateLimit}
     *     so that an explicit-clear DELETE truly resets per-model gate state — not just the
     *     DB column. Without this, the cached {@link RateLimiter} would linger for the JVM's
     *     lifetime and a subsequent re-set of the same model's RPM could race against the
     *     stale instance during the rebuild path's {@code prev != rpm} check
     *     (Anti-pattern A1 in {@code docs/plans-execute-whats-left.md}). Idempotent: a clear
     *     on a model that never had a limiter is a no-op.
     */
    public void invalidate(String modelId) {
        if (modelId == null) return;
        registry.remove(KEY_PREFIX + modelId);
        appliedRpm.remove(modelId);
    }

    private Integer lookupRpm(String modelId) {
        Optional<ModelEntity> entity = modelOperations.getModelEntityById(modelId);
        return entity.map(ModelEntity::getRateLimitRpm).orElse(null);
    }

    private RateLimiter limiterFor(String modelId, int rpm) {
        String key = KEY_PREFIX + modelId;
        Integer prev = appliedRpm.get(modelId);
        if (prev == null || prev != rpm) {
            // Configuration changed (or first observation) — drop the stale limiter so the
            // factory below builds a fresh one with the new rpm.
            registry.remove(key);
            appliedRpm.put(modelId, rpm);
        }
        return registry.rateLimiter(key, () -> RateLimiterConfig.custom()
                .limitForPeriod(rpm)
                .limitRefreshPeriod(REFRESH_PERIOD)
                .timeoutDuration(Duration.ZERO)
                .build());
    }
}
