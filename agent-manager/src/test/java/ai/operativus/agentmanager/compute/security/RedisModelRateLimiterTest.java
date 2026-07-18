package ai.operativus.agentmanager.compute.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: pin A5 distributed rate-limit semantics. The limiter MUST
 * - INCR a per-(model, epoch-minute) key on every acquire,
 * - apply a 90-second EXPIRE only on the FIRST hit (count == 1) per minute window,
 * - return true while count <= rpm and false afterwards,
 * - swap to a fresh key when the wall clock crosses a minute boundary,
 * - tolerate a null INCR result (Redis unreachability surrogate) by returning false.
 *
 * <p>Uses a stepped {@link Clock} so the boundary-reset case is deterministic. The Redis
 * round-trip is mocked via {@link StringRedisTemplate} stubs — no Testcontainers in this
 * unit test (the integration test for the full guard fallback path can add one).
 */
class RedisModelRateLimiterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-27T20:30:00Z");
    private static final long FIXED_EPOCH_MINUTE = FIXED_NOW.getEpochSecond() / 60;
    private static final long NEXT_EPOCH_MINUTE = FIXED_EPOCH_MINUTE + 1;

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    private SteppedClock clock;
    private RedisModelRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        clock = new SteppedClock(FIXED_NOW);
        limiter = new RedisModelRateLimiter(redis, clock);
    }

    @Test
    void tryAcquire_subRpm_returnsTrueAndAppliesExpireOnlyOnFirstHit() {
        String key = "agm:rl:model:m-1:" + FIXED_EPOCH_MINUTE;
        when(valueOps.increment(key))
                .thenReturn(1L)
                .thenReturn(2L)
                .thenReturn(3L);

        assertThat(limiter.tryAcquire("m-1", 5)).isTrue();
        assertThat(limiter.tryAcquire("m-1", 5)).isTrue();
        assertThat(limiter.tryAcquire("m-1", 5)).isTrue();

        // EXPIRE applied exactly once — on the first hit, when count == 1.
        verify(redis, times(1)).expire(eq(key), eq(Duration.ofSeconds(90)));
    }

    @Test
    void tryAcquire_overRpm_returnsFalse() {
        String key = "agm:rl:model:m-2:" + FIXED_EPOCH_MINUTE;
        when(valueOps.increment(key))
                .thenReturn(1L)
                .thenReturn(2L)
                .thenReturn(3L)
                .thenReturn(4L);

        assertThat(limiter.tryAcquire("m-2", 3)).isTrue();
        assertThat(limiter.tryAcquire("m-2", 3)).isTrue();
        assertThat(limiter.tryAcquire("m-2", 3)).isTrue();
        assertThat(limiter.tryAcquire("m-2", 3))
                .as("4th hit when rpm=3 must be rejected")
                .isFalse();
    }

    @Test
    void tryAcquire_atMinuteBoundary_swapsToNewKey() {
        String firstKey = "agm:rl:model:m-3:" + FIXED_EPOCH_MINUTE;
        String secondKey = "agm:rl:model:m-3:" + NEXT_EPOCH_MINUTE;
        when(valueOps.increment(firstKey)).thenReturn(1L);
        when(valueOps.increment(secondKey)).thenReturn(1L);

        assertThat(limiter.tryAcquire("m-3", 1)).isTrue();
        // Advance the clock by 60 seconds — epoch-minute increments by one.
        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire("m-3", 1))
                .as("new minute = fresh window = admit the first hit again")
                .isTrue();

        verify(valueOps, times(1)).increment(firstKey);
        verify(valueOps, times(1)).increment(secondKey);
    }

    @Test
    void tryAcquire_redisIncrReturnsNull_returnsFalseSoCallerCanFallBack() {
        // INCR returning null surrogates a Redis-side error in Spring Data Redis. The guard
        // catches DataAccessException upstream; here we just verify that a null reply doesn't
        // get treated as "admitted with count=0".
        when(valueOps.increment(any(String.class))).thenReturn(null);

        assertThat(limiter.tryAcquire("m-4", 100))
                .as("null INCR reply must NOT be treated as admit")
                .isFalse();
        verify(redis, never()).expire(any(String.class), any(Duration.class));
    }

    @Test
    void tryAcquire_keyShapeMatchesNamespacedConvention() {
        when(valueOps.increment(any(String.class))).thenReturn(1L);

        limiter.tryAcquire("partner-shared-key-7", 10);

        verify(valueOps).increment("agm:rl:model:partner-shared-key-7:" + FIXED_EPOCH_MINUTE);
    }

    /** A frozen-then-advanceable clock so boundary-crossing is deterministic. */
    private static final class SteppedClock extends Clock {
        private Instant now;

        SteppedClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
