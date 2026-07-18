package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.RateLimitExceededException;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin the Micrometer counters for the A5 distributed path. Three
 * outcomes share the same metric name {@code agm.rate_limit.distributed} with an
 * {@code outcome} tag — Grafana / PromQL can express "fallback ratio = fallback /
 * (acquired + rejected + fallback)" with a single query.
 *
 * <p>Pinned outcomes:
 * <ul>
 *   <li>{@code outcome=acquired} — Redis admitted the request.</li>
 *   <li>{@code outcome=rejected} — Redis rejected as over-ceiling (throws RLE).</li>
 *   <li>{@code outcome=fallback} — Redis was unreachable, local gate took over.</li>
 * </ul>
 *
 * <p>Drift in the metric name or tag values breaks downstream alerts; treat this test as
 * the contract.
 */
class ModelRateLimitGuardMetricsTest {

    private ModelOperations modelOperations;
    private RateLimiterRegistry registry;
    private RedisModelRateLimiter redisLimiter;
    private SimpleMeterRegistry meters;
    private ModelRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        modelOperations = mock(ModelOperations.class);
        registry = RateLimiterRegistry.ofDefaults();
        redisLimiter = mock(RedisModelRateLimiter.class);
        meters = new SimpleMeterRegistry();
        guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, meters, true);
    }

    @Test
    void metricName_isStable() {
        assertThat(ModelRateLimitGuard.METRIC_NAME)
                .as("dashboards alert on this exact name — drift breaks alerts")
                .isEqualTo("agm.rate_limit.distributed");
    }

    @Test
    void redisAdmit_incrementsAcquiredOutcomeOnly() {
        when(modelOperations.getModelEntityById("m-a"))
                .thenReturn(Optional.of(modelWithRpm(5)));
        when(redisLimiter.tryAcquire("m-a", 5)).thenReturn(true);

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-a"));

        assertThat(count("acquired")).isEqualTo(1.0);
        assertThat(count("rejected")).isZero();
        assertThat(count("fallback")).isZero();
    }

    @Test
    void redisReject_incrementsRejectedOutcomeOnly_andThrows() {
        when(modelOperations.getModelEntityById("m-b"))
                .thenReturn(Optional.of(modelWithRpm(1)));
        when(redisLimiter.tryAcquire("m-b", 1)).thenReturn(false);

        assertThatThrownBy(() -> guard.acquireOrThrow("m-b"))
                .isInstanceOf(RateLimitExceededException.class);

        assertThat(count("rejected")).isEqualTo(1.0);
        assertThat(count("acquired")).isZero();
        assertThat(count("fallback")).isZero();
    }

    @Test
    void redisOutage_incrementsFallbackOutcomeOnly_andLocalAdmits() {
        when(modelOperations.getModelEntityById("m-c"))
                .thenReturn(Optional.of(modelWithRpm(2)));
        when(redisLimiter.tryAcquire("m-c", 2))
                .thenThrow(new TestRedisOutage("connection refused"));

        // Local Resilience4j fallback admits the request — customer SLO survives.
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-c"));

        assertThat(count("fallback")).isEqualTo(1.0);
        assertThat(count("acquired"))
                .as("local-fallback admits are NOT counted as distributed-acquired")
                .isZero();
        assertThat(count("rejected")).isZero();
    }

    @Test
    void mixedTraffic_eachOutcomeAccumulatesIndependently() {
        when(modelOperations.getModelEntityById("m-mix"))
                .thenReturn(Optional.of(modelWithRpm(10)));
        when(redisLimiter.tryAcquire("m-mix", 10))
                .thenReturn(true)                                          // 1 acquired
                .thenReturn(true)                                          // 2 acquired
                .thenReturn(false)                                         // rejected (throws)
                .thenThrow(new TestRedisOutage("blip"))                    // fallback (admits)
                .thenReturn(true);                                         // 3 acquired

        guard.acquireOrThrow("m-mix");
        guard.acquireOrThrow("m-mix");
        assertThatThrownBy(() -> guard.acquireOrThrow("m-mix"))
                .isInstanceOf(RateLimitExceededException.class);
        guard.acquireOrThrow("m-mix");
        guard.acquireOrThrow("m-mix");

        assertThat(count("acquired")).isEqualTo(3.0);
        assertThat(count("rejected")).isEqualTo(1.0);
        assertThat(count("fallback")).isEqualTo(1.0);
    }

    @Test
    void flagOff_legacyPath_doesNotIncrementAnyDistributedCounter() {
        ModelRateLimitGuard legacyGuard = new ModelRateLimitGuard(
                modelOperations, registry, redisLimiter, meters, false);
        when(modelOperations.getModelEntityById("m-legacy"))
                .thenReturn(Optional.of(modelWithRpm(2)));

        legacyGuard.acquireOrThrow("m-legacy");
        legacyGuard.acquireOrThrow("m-legacy");

        assertThat(count("acquired")).isZero();
        assertThat(count("rejected")).isZero();
        assertThat(count("fallback")).isZero();
    }

    private double count(String outcome) {
        Counter c = meters.find(ModelRateLimitGuard.METRIC_NAME).tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    private static ModelEntity modelWithRpm(Integer rpm) {
        ModelEntity e = new ModelEntity();
        e.setId("m");
        e.setName("m");
        e.setProvider("OPENAI");
        e.setModelName("gpt-x");
        e.setRateLimitRpm(rpm);
        return e;
    }

    private static final class TestRedisOutage extends DataAccessException {
        TestRedisOutage(String msg) { super(msg); }
    }
}
