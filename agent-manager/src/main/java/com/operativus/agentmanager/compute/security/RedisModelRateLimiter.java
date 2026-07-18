package com.operativus.agentmanager.compute.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Domain Responsibility: A5 distributed per-model rate limiter (see
 * {@code docs/plans/agm-a5-distributed-rate-limit.md}). Replaces the JVM-local
 * Resilience4j path with a Redis-backed fixed-window counter so the configured
 * RPM ceiling holds globally across all replicas, not just per-process.
 *
 * <p>Algorithm: fixed-window. Key {@code agm:rl:model:{id}:{epochMinute}} is
 * INCR'd on every acquire; on the FIRST hit per minute, an EXPIRE of 90s is
 * applied so the key self-cleans without a sweeper. If {@code count <= rpm} the
 * call is allowed; otherwise rejected. Boundary-burst trade-off (up to 2× rpm
 * across a 2-second window straddling minute boundary) is documented and
 * accepted per the plan.
 *
 * <p>Failure model: throws {@link org.springframework.dao.DataAccessException}
 * (or {@link org.springframework.data.redis.RedisConnectionFailureException}) on
 * Redis unreachability. The caller ({@code ModelRateLimitGuard}) catches and
 * falls back to the local Resilience4j gate so customer SLO is preserved during
 * Redis outages — fail-OPEN by design.
 *
 * <p>State: Stateless aside from the injected {@link StringRedisTemplate} and
 * {@link Clock}. Safe for concurrent use; INCR is atomic on Redis.
 */
@Component
public class RedisModelRateLimiter {

    /** Public so cross-package integration tests can target the same key namespace
     *  for setup/cleanup. Pinned by tests; drift breaks the cleanup paths. */
    public static final String KEY_PREFIX = "agm:rl:model:";
    private static final Duration KEY_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public RedisModelRateLimiter(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /** Test seam: lets a unit or integration test inject a frozen, stepped, or system clock.
     *  Public so cross-package integration tests can construct against a real Redis container
     *  without forcing the test into this package. */
    public RedisModelRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * @summary Atomically counts this acquisition against the per-minute window
     *          for {@code modelId} and reports whether the configured ceiling
     *          {@code rpm} has been honored.
     * @return {@code true} if the call is within ceiling, {@code false} if over.
     */
    public boolean tryAcquire(String modelId, int rpm) {
        long epochMinute = clock.instant().getEpochSecond() / 60;
        String key = KEY_PREFIX + modelId + ":" + epochMinute;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, KEY_TTL);
        }
        return count != null && count <= rpm;
    }
}
