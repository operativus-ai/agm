package com.operativus.agentmanager.control.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Domain Responsibility: In-process Caffeine-backed implementation of {@link SseTokenStore}.
 *   Default for dev/local profiles. Tokens evaporate on JVM restart — acceptable for
 *   {@code agm.sse.token-store=caffeine} which is explicitly the single-node default.
 * State: One Caffeine cache instance per {@code DataRetentionService} bean lifetime; safe for
 *   concurrent access (Caffeine is internally thread-safe).
 *
 * <p><b>Single-use enforcement:</b> {@link #validateAndConsume(String, String)} uses
 * {@code asMap().remove(token)} — a single atomic CHM operation that returns the prior value
 * iff present. Any second caller observes the token as already consumed.
 *
 * <p><b>Mismatched-run handling:</b> when the token exists but the runId does not match the
 * caller's expected run, the entry is reinserted with its remaining TTL recalculated against
 * the original {@code expiresAt}. The legitimate caller (correct runId) can still consume
 * within the original 60s window.
 */
@Service
@ConditionalOnProperty(name = "agm.sse.token-store", havingValue = "caffeine", matchIfMissing = true)
public class CaffeineSseTokenStore implements SseTokenStore {

    private final Cache<String, SseTokenClaim> cache;
    private final Clock clock;

    @Autowired
    public CaffeineSseTokenStore(
            @Value("${agm.sse.token.ttl-seconds:60}") long ttlSeconds) {
        this(ttlSeconds, Ticker.systemTicker(), Clock.systemUTC());
    }

    /**
     * Test-only constructor: lets tests inject a fake {@link Ticker}/{@link Clock} pair so TTL
     * expiry can be advanced without {@code Thread.sleep}.
     */
    public CaffeineSseTokenStore(long ttlSeconds, Ticker ticker, Clock clock) {
        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        this.clock = clock;
    }

    @Override
    public void store(String token, SseTokenClaim claim, long ttlSeconds) {
        cache.put(token, claim);
    }

    @Override
    public Optional<SseTokenClaim> validateAndConsume(String token, String expectedRunId) {
        SseTokenClaim claim = cache.asMap().remove(token);
        if (claim == null) return Optional.empty();
        if (Instant.now(clock).isAfter(claim.expiresAt())) return Optional.empty();
        if (!claim.runId().equals(expectedRunId)) {
            // Run mismatch — reinsert so the legitimate caller can still consume.
            cache.put(token, claim);
            return Optional.empty();
        }
        return Optional.of(claim);
    }

    @Override
    public Optional<SseTokenClaim> peek(String token) {
        SseTokenClaim claim = cache.getIfPresent(token);
        if (claim == null) return Optional.empty();
        if (Instant.now(clock).isAfter(claim.expiresAt())) return Optional.empty();
        return Optional.of(claim);
    }
}
