package ai.operativus.agentmanager.compute.security;

import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.RateLimitExceededException;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin the A5 hybrid path on {@link ModelRateLimitGuard}. With the
 * {@code agentmanager.rate-limit.distributed-model.enabled} flag ON the guard MUST
 * - ask Redis first via {@link RedisModelRateLimiter#tryAcquire},
 * - throw {@link RateLimitExceededException} on Redis-reported over-ceiling without ever
 *   touching the local Resilience4j path,
 * - on Redis unreachability ({@link DataAccessException} or
 *   {@link org.springframework.data.redis.RedisConnectionFailureException}) fall back to
 *   the local Resilience4j gate so the customer SLO survives a Redis outage.
 *
 * <p>With the flag OFF behaviour MUST be byte-for-byte identical to today — no Redis
 * traffic, the existing {@code ModelRateLimitGuardTest} suite already pins that path.
 */
class ModelRateLimitGuardDistributedTest {

    private ModelOperations modelOperations;
    private RateLimiterRegistry registry;
    private RedisModelRateLimiter redisLimiter;

    @BeforeEach
    void setUp() {
        modelOperations = mock(ModelOperations.class);
        registry = RateLimiterRegistry.ofDefaults();
        redisLimiter = mock(RedisModelRateLimiter.class);
    }

    @Test
    void flagOff_neverConsultsRedis_keepsLegacyPath() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, false);
        when(modelOperations.getModelEntityById("m-1"))
                .thenReturn(Optional.of(modelWithRpm(2)));

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-1"));
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-1"));

        verify(redisLimiter, never()).tryAcquire(anyString(), anyInt());
        assertThat(guard.registeredLimiterCount())
                .as("local Resilience4j limiter built on first hit")
                .isEqualTo(1);
    }

    @Test
    void flagOn_redisAdmits_doesNotTouchLocalRegistry() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);
        when(modelOperations.getModelEntityById("m-2"))
                .thenReturn(Optional.of(modelWithRpm(5)));
        when(redisLimiter.tryAcquire("m-2", 5)).thenReturn(true);

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-2"));

        verify(redisLimiter, times(1)).tryAcquire("m-2", 5);
        assertThat(guard.registeredLimiterCount())
                .as("distributed path took the decision; no local limiter materialised")
                .isZero();
    }

    @Test
    void flagOn_redisRejects_throwsWithoutFallingBackToLocalAdmit() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);
        when(modelOperations.getModelEntityById("m-3"))
                .thenReturn(Optional.of(modelWithRpm(1)));
        when(redisLimiter.tryAcquire("m-3", 1)).thenReturn(false);

        assertThatThrownBy(() -> guard.acquireOrThrow("m-3"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("1 requests/minute");

        verify(redisLimiter, times(1)).tryAcquire("m-3", 1);
        assertThat(guard.registeredLimiterCount())
                .as("Redis decision is final; the local registry is NOT consulted")
                .isZero();
    }

    @Test
    void flagOn_redisOutage_fallsBackToLocalGate_andAdmits() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);
        when(modelOperations.getModelEntityById("m-4"))
                .thenReturn(Optional.of(modelWithRpm(3)));
        when(redisLimiter.tryAcquire("m-4", 3))
                .thenThrow(new TestRedisOutage("connection refused"));

        // Customer SLO survives the outage — first hit admits via local Resilience4j.
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-4"));

        verify(redisLimiter, times(1)).tryAcquire("m-4", 3);
        assertThat(guard.registeredLimiterCount())
                .as("local fallback materialised a Resilience4j limiter")
                .isEqualTo(1);
    }

    @Test
    void flagOn_redisOutage_localGateStillEnforcesCeiling() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);
        when(modelOperations.getModelEntityById("m-5"))
                .thenReturn(Optional.of(modelWithRpm(2)));
        when(redisLimiter.tryAcquire("m-5", 2))
                .thenThrow(new TestRedisOutage("network blip"));

        // First two admitted via fallback; third rejected by local Resilience4j.
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-5"));
        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-5"));
        assertThatThrownBy(() -> guard.acquireOrThrow("m-5"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void flagOn_modelWithoutOverride_skipsRedis_isNoOp() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);
        when(modelOperations.getModelEntityById("m-6"))
                .thenReturn(Optional.of(modelWithRpm(null)));

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow("m-6"));

        verify(redisLimiter, never()).tryAcquire(anyString(), anyInt());
    }

    @Test
    void flagOn_nullModelId_skipsRedis_isNoOp() {
        ModelRateLimitGuard guard = new ModelRateLimitGuard(modelOperations, registry, redisLimiter, true);

        assertThatNoException().isThrownBy(() -> guard.acquireOrThrow(null));

        verify(redisLimiter, never()).tryAcquire(anyString(), anyInt());
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

    /** Minimal {@link DataAccessException} subclass for the outage path — Spring's
     *  {@code RedisConnectionFailureException} would also work but pulls a heavier
     *  dependency surface into the test classpath. */
    private static final class TestRedisOutage extends DataAccessException {
        TestRedisOutage(String msg) { super(msg); }
    }
}
