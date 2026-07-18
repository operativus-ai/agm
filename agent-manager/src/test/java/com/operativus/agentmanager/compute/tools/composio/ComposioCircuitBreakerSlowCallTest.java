package com.operativus.agentmanager.compute.tools.composio;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins the slow-call-rate trip path on the Composio circuit breaker
 *   (audit P2.7). Latency spikes (Composio backend slow-but-healthy) return 200s but eat
 *   connection-pool capacity until pending-acquire timeouts cascade into failures —
 *   the slow-call threshold opens the breaker proactively before that cascade.
 *
 * <p>Pure JUnit — builds a {@link CircuitBreaker} with the same shape as
 *   {@code ComposioWebClientConfig#composioCircuitBreaker} and exercises the
 *   slow-call path end-to-end via {@link CircuitBreaker#executeSupplier}.
 *
 * State: Stateless (each test instantiates a fresh breaker).
 */
class ComposioCircuitBreakerSlowCallTest {

    private static final int SLOW_CALL_RATE_THRESHOLD_PERCENT = 80;
    private static final int MIN_CALLS = 5;
    private static final Duration SLOW_CALL_DURATION = Duration.ofMillis(50);

    @Test
    void breakerOpensWhenSlowCallRateExceedsThreshold() {
        CircuitBreaker breaker = newBreaker();

        // 5 calls all slower than the slow-call duration → 100% slow rate ≥ 80% threshold.
        for (int i = 0; i < MIN_CALLS; i++) {
            breaker.executeSupplier(() -> {
                sleep(SLOW_CALL_DURATION.plusMillis(20));
                return "ok"; // returns successfully — slow-call path, NOT failure path
            });
        }

        assertThat(breaker.getState())
                .as("Breaker must OPEN once slow-call rate ≥ threshold despite all calls succeeding")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void breakerStaysClosedWhenCallsAreFastEvenIfManyComplete() {
        CircuitBreaker breaker = newBreaker();

        // 5 fast calls → 0% slow rate, 0% failure rate → breaker stays CLOSED.
        for (int i = 0; i < MIN_CALLS; i++) {
            breaker.executeSupplier(() -> "fast");
        }

        assertThat(breaker.getState())
                .as("Breaker must remain CLOSED when no slow calls and no failures occur")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerStaysClosedWhenSlowCallRateIsBelowThreshold() {
        CircuitBreaker breaker = newBreaker();

        // 1 slow + 4 fast = 20% slow rate, well below the 80% threshold.
        breaker.executeSupplier(() -> {
            sleep(SLOW_CALL_DURATION.plusMillis(20));
            return "slow";
        });
        for (int i = 0; i < MIN_CALLS - 1; i++) {
            breaker.executeSupplier(() -> "fast");
        }

        assertThat(breaker.getState())
                .as("Breaker must remain CLOSED when slow-call rate is below threshold")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * Mirrors the production config in {@code ComposioWebClientConfig#composioCircuitBreaker}.
     * Keep the threshold/window/duration values in sync with that bean so this test surfaces
     * a regression when production defaults drift.
     */
    private static CircuitBreaker newBreaker() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(MIN_CALLS)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(20)
                .slowCallRateThreshold(SLOW_CALL_RATE_THRESHOLD_PERCENT)
                .slowCallDurationThreshold(SLOW_CALL_DURATION)
                .build();
        return CircuitBreaker.of("composio-slow-call-test-" + System.nanoTime(), cfg);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }
}
