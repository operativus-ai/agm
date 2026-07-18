package ai.operativus.agentmanager.integration.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ai.operativus.agentmanager.compute.security.ModelRateLimitGuard;
import ai.operativus.agentmanager.compute.security.RedisModelRateLimiter;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.exception.RateLimitExceededException;
import ai.operativus.agentmanager.core.registry.ModelOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin the multi-instance correctness contract of A5 distributed
 *   rate limiting (`agm-a5-distributed-rate-limit-23.md` §5). Exercises the full path —
 *   real Redis (Testcontainer), real {@link RedisModelRateLimiter}, real
 *   {@link ModelRateLimitGuard} — across two guard instances simulating two replicas.
 *
 * <p>What the existing tests pin and why this is different:
 *   <ul>
 *     <li>{@code RedisModelRateLimiterTest} — unit, mocks the {@link StringRedisTemplate},
 *         pins the fixed-window INCR/EXPIRE algorithm.
 *     <li>{@code ModelRateLimitGuardDistributedTest} — unit, mocks the limiter, pins the
 *         hybrid-path branching.
 *     <li>This class — integration, exercises the actual Redis-server-side INCR semantics
 *         AND proves the cap holds when the same Redis is shared between two guards.
 *   </ul>
 *
 * <p>Cross-replica simulation: two {@link ModelRateLimitGuard} instances are constructed by
 *   hand. Each carries its OWN {@link RateLimiterRegistry} (own local-fallback bucket) and
 *   its OWN {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry}; they SHARE a
 *   single {@link RedisModelRateLimiter} pointed at the test container. The flag-OFF case
 *   proves the local paths diverge in absence of the distributed gate — empirical isolation
 *   guarantee for case (a).
 *
 * State: Stateless across tests (per-test JDBC truncate via {@link BaseIntegrationTest}).
 *   The Redis container is shared per JVM via static init.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ModelRateLimitGuardDistributedRuntimeTest extends BaseIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private StringRedisTemplate stringRedisTemplate;

    private static final String MODEL_ID = "distributed-test-model";
    private static final int RPM = 5;

    private Logger guardLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        guardLogger = (Logger) LoggerFactory.getLogger(ModelRateLimitGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
        // Wipe any leftover keys from prior tests in the same JVM so each case starts at 0.
        stringRedisTemplate.delete(stringRedisTemplate.keys(RedisModelRateLimiter.KEY_PREFIX + "*"));
    }

    @AfterEach
    void detachAppender() {
        guardLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void flagOn_twoInstancesSharingOneRedis_combinedCapHoldsAtRpm() {
        ModelOperations modelOps = mockModelOpsWithRpm(MODEL_ID, RPM);
        RedisModelRateLimiter sharedLimiter = new RedisModelRateLimiter(
                stringRedisTemplate, Clock.systemUTC());

        // Two guards, each with its OWN RateLimiterRegistry — the local-path bucket is
        // independent per "replica," matching production topology.
        ModelRateLimitGuard guardA = new ModelRateLimitGuard(
                modelOps, RateLimiterRegistry.ofDefaults(), sharedLimiter, true);
        ModelRateLimitGuard guardB = new ModelRateLimitGuard(
                modelOps, RateLimiterRegistry.ofDefaults(), sharedLimiter, true);

        // Drive 3 acquires through guardA, 2 through guardB → 5 total = exactly RPM.
        assertDoesNotThrow(() -> guardA.acquireOrThrow(MODEL_ID));
        assertDoesNotThrow(() -> guardA.acquireOrThrow(MODEL_ID));
        assertDoesNotThrow(() -> guardA.acquireOrThrow(MODEL_ID));
        assertDoesNotThrow(() -> guardB.acquireOrThrow(MODEL_ID));
        assertDoesNotThrow(() -> guardB.acquireOrThrow(MODEL_ID));

        // 6th call from EITHER guard must reject — proves the cap is shared, not per-JVM.
        assertThatThrownBy(() -> guardA.acquireOrThrow(MODEL_ID))
                .as("once combined acquires reach RPM, ANY further call from EITHER guard must reject")
                .isInstanceOf(RateLimitExceededException.class);
        assertThatThrownBy(() -> guardB.acquireOrThrow(MODEL_ID))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void flagOn_redisFailureFallsBackToLocalPath_emitsCanonicalWarn() {
        ModelOperations modelOps = mockModelOpsWithRpm(MODEL_ID, RPM);
        // Construct a separate RedisModelRateLimiter whose StringRedisTemplate ALWAYS throws —
        // simulates Redis unreachability. We mock at the Spring-DAO layer (not at Lettuce's
        // raw socket) because the production catch is on `DataAccessException`.
        StringRedisTemplate brokenTemplate = mock(StringRedisTemplate.class);
        when(brokenTemplate.opsForValue()).thenThrow(
                new QueryTimeoutException("simulated Redis unreachability"));
        RedisModelRateLimiter brokenLimiter = new RedisModelRateLimiter(
                brokenTemplate, Clock.systemUTC());

        ModelRateLimitGuard guard = new ModelRateLimitGuard(
                modelOps, RateLimiterRegistry.ofDefaults(), brokenLimiter, true);

        // Call must NOT throw — local-fallback path takes over and the local bucket is fresh.
        assertDoesNotThrow(() -> guard.acquireOrThrow(MODEL_ID),
                "Redis failure must surface as fail-OPEN via local-Resilience4j fallback");

        assertThat(appender.list)
                .as("WARN log uses the canonical REDIS_FALLBACK_LOG_MESSAGE constant")
                .anyMatch(ev -> ev.getLevel() == Level.WARN
                        && ev.getMessage().equals(ModelRateLimitGuard.REDIS_FALLBACK_LOG_MESSAGE));
    }

    @Test
    void flagOff_twoInstancesEnforceIndependentCaps_eachJvmAllowsUpToRpm() {
        ModelOperations modelOps = mockModelOpsWithRpm(MODEL_ID, RPM);
        RedisModelRateLimiter sharedLimiter = new RedisModelRateLimiter(
                stringRedisTemplate, Clock.systemUTC());

        // Same shared limiter, but distributedEnabled=false — proves the local paths diverge
        // when the distributed gate is bypassed (regression pin for today's default behavior).
        ModelRateLimitGuard guardA = new ModelRateLimitGuard(
                modelOps, RateLimiterRegistry.ofDefaults(), sharedLimiter, false);
        ModelRateLimitGuard guardB = new ModelRateLimitGuard(
                modelOps, RateLimiterRegistry.ofDefaults(), sharedLimiter, false);

        // Each guard independently allows up to RPM in its own local bucket.
        for (int i = 0; i < RPM; i++) {
            int idx = i;
            assertDoesNotThrow(() -> guardA.acquireOrThrow(MODEL_ID),
                    "guardA call #" + idx + " under its own RPM cap must succeed");
            assertDoesNotThrow(() -> guardB.acquireOrThrow(MODEL_ID),
                    "guardB call #" + idx + " under its own RPM cap must succeed");
        }
        // Combined throughput is 2 × RPM with the flag OFF — exactly the divergence A5 fixes.
    }

    private static ModelOperations mockModelOpsWithRpm(String modelId, int rpm) {
        ModelOperations modelOps = mock(ModelOperations.class);
        ModelEntity entity = mock(ModelEntity.class);
        when(entity.getRateLimitRpm()).thenReturn(rpm);
        when(modelOps.getModelEntityById(modelId)).thenReturn(Optional.of(entity));
        return modelOps;
    }
}
