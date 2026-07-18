package com.operativus.agentmanager.control.security;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineSseTokenStoreTest {

    private static final long TTL_SECONDS = 60L;

    private AtomicLong nanos;
    private CaffeineSseTokenStore store;

    @BeforeEach
    void setUp() {
        // Fake clock starts at a fixed reference instant; both Caffeine's Ticker and the
        // Clock used for the store's defense-in-depth expiry check advance off the same
        // backing nano counter. Tests advance time by mutating `nanos`.
        nanos = new AtomicLong(0L);
        Ticker ticker = nanos::get;
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        // For the wall-clock check we want it to advance with `nanos` too — wrap.
        Clock advancing = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.EPOCH.plusNanos(nanos.get()); }
        };
        store = new CaffeineSseTokenStore(TTL_SECONDS, ticker, advancing);
    }

    private SseTokenClaim sampleClaim(String runId) {
        return new SseTokenClaim(
                runId, "user-1", "org-1", List.of("ROLE_USER"),
                Instant.EPOCH.plusSeconds(TTL_SECONDS));
    }

    @Test
    void validateAndConsume_HappyPath_ReturnsClaim() {
        store.store("tok-1", sampleClaim("run-A"), TTL_SECONDS);

        Optional<SseTokenClaim> result = store.validateAndConsume("tok-1", "run-A");

        assertThat(result).isPresent();
        assertThat(result.get().runId()).isEqualTo("run-A");
    }

    @Test
    void validateAndConsume_SecondCallReturnsEmpty_SingleUseEnforced() {
        store.store("tok-2", sampleClaim("run-A"), TTL_SECONDS);

        store.validateAndConsume("tok-2", "run-A"); // first consume
        Optional<SseTokenClaim> second = store.validateAndConsume("tok-2", "run-A");

        assertThat(second).isEmpty();
    }

    @Test
    void validateAndConsume_MismatchedRun_DoesNotConsume() {
        store.store("tok-3", sampleClaim("run-A"), TTL_SECONDS);

        Optional<SseTokenClaim> wrongRun = store.validateAndConsume("tok-3", "run-B");
        assertThat(wrongRun).isEmpty();

        // Token must still be present for the legitimate run-A caller.
        Optional<SseTokenClaim> rightRun = store.validateAndConsume("tok-3", "run-A");
        assertThat(rightRun).isPresent();
    }

    @Test
    void validateAndConsume_AfterTtlExpiry_ReturnsEmpty() {
        store.store("tok-4", sampleClaim("run-A"), TTL_SECONDS);

        // Advance fake time past the 60s TTL. Caffeine's expireAfterWrite is keyed off the
        // injected Ticker, so this triggers expiry without real Thread.sleep.
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(TTL_SECONDS + 1));

        Optional<SseTokenClaim> result = store.validateAndConsume("tok-4", "run-A");
        assertThat(result).isEmpty();
    }

    @Test
    void peek_DoesNotConsume() {
        store.store("tok-5", sampleClaim("run-A"), TTL_SECONDS);

        store.peek("tok-5");
        store.peek("tok-5");
        Optional<SseTokenClaim> consumed = store.validateAndConsume("tok-5", "run-A");

        assertThat(consumed).isPresent();
    }
}
