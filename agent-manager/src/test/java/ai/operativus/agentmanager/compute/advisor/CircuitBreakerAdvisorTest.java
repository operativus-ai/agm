package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.compute.advisor.CircuitBreakerAdvisor.ProviderUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link CircuitBreakerAdvisor} — wraps LLM chain calls in a per-provider
 * Resilience4j CircuitBreaker keyed by the resolved provider
 * (openai/anthropic/google/ollama/cohere/...).
 *
 * <p>Direct instantiation, no Spring context. Uses a test-profile config —
 * {@code sliding-window-size=3}, {@code minimum-number-of-calls=2},
 * {@code wait-duration-open-ms=200}, {@code failure-rate-threshold=50%} — so
 * CLOSED→OPEN→HALF_OPEN transitions finish sub-second. Production values
 * ({@code 30s} wait) are unfit for test pinning; that tradeoff is why the
 * Resilience4j thresholds are externalised through
 * {@code agent.guardrails.circuit-breaker.*}.
 *
 * <p>Slow-call behavior is exercised via the test-only nano-clock constructor
 * ({@link CircuitBreakerAdvisor#CircuitBreakerAdvisor(float, int, int, long, int, long, float, java.util.function.LongSupplier, io.micrometer.core.instrument.MeterRegistry)})
 * so the slow-call rate calculation is deterministic without
 * {@code Thread.sleep(slowCallDurationMs)} per call.
 */
class CircuitBreakerAdvisorTest {

    private static final float FAILURE_RATE_THRESHOLD = 50f;
    private static final int SLIDING_WINDOW_SIZE = 3;
    private static final int MIN_CALLS = 2;
    private static final long WAIT_OPEN_MS = 200;
    private static final int PERMITTED_HALF_OPEN = 2;
    private static final long SLOW_CALL_DURATION_MS = 5_000;
    private static final float SLOW_CALL_RATE_THRESHOLD = 80f;

    private CircuitBreakerAdvisor advisor;
    private CallAdvisorChain callChain;
    private StreamAdvisorChain streamChain;
    private AtomicLong fakeNanos;

    @BeforeEach
    void setUp() {
        fakeNanos = new AtomicLong(0L);
        advisor = new CircuitBreakerAdvisor(
                FAILURE_RATE_THRESHOLD, SLIDING_WINDOW_SIZE, MIN_CALLS,
                WAIT_OPEN_MS, PERMITTED_HALF_OPEN, SLOW_CALL_DURATION_MS, SLOW_CALL_RATE_THRESHOLD,
                fakeNanos::get, new SimpleMeterRegistry());
        callChain = mock(CallAdvisorChain.class);
        streamChain = mock(StreamAdvisorChain.class);
    }

    @Test
    void nameAndOrder_matchAdvisorContract() {
        assertThat(advisor.getName()).isEqualTo("CircuitBreakerAdvisor");
        assertThat(advisor.getOrder()).isEqualTo(1);
    }

    @Test
    void circuitOpensAfterThresholdFailuresInSlidingWindow() {
        ChatClientRequest req = requestForModel("gpt-4o");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("provider down"));

        // 2 failures satisfies min-calls=2; failure rate 100% > 50% threshold → circuit opens.
        for (int i = 0; i < MIN_CALLS; i++) {
            assertThatThrownBy(() -> advisor.adviseCall(req, callChain))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("provider down");
        }

        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "OPEN");
    }

    @Test
    void openCircuitFailsFastWithProviderUnavailableException() {
        ChatClientRequest req = requestForModel("gpt-4o");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("provider down"));
        tripBreaker(req);

        reset(callChain);

        assertThatThrownBy(() -> advisor.adviseCall(req, callChain))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("Circuit breaker is open");

        verify(callChain, never()).nextCall(any());
    }

    @Test
    void providerUnavailableExceptionCarriesProviderKey() {
        ChatClientRequest req = requestForModel("claude-3-5-sonnet-20240620");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("down"));
        tripBreaker(req);

        try {
            advisor.adviseCall(req, callChain);
            throw new AssertionError("expected ProviderUnavailableException");
        } catch (ProviderUnavailableException e) {
            assertThat(e.getProvider()).isEqualTo("anthropic");
        }
    }

    @Test
    void perProviderBreakersAreIsolated() {
        ChatClientRequest openaiReq = requestForModel("gpt-4o");
        ChatClientRequest googleReq = requestForModel("gemini-2.5-pro");

        when(callChain.nextCall(any())).thenThrow(new RuntimeException("openai down"));
        tripBreaker(openaiReq);

        // Google breaker is untouched — a success on it must pass through.
        reset(callChain);
        ChatClientResponse ok = mock(ChatClientResponse.class);
        when(callChain.nextCall(any())).thenReturn(ok);

        ChatClientResponse result = advisor.adviseCall(googleReq, callChain);

        assertThat(result).isSameAs(ok);
        Map<String, String> health = advisor.getProviderHealth();
        assertThat(health).containsEntry("llm-provider:openai", "OPEN");
        assertThat(health).containsEntry("llm-provider:google", "CLOSED");
    }

    @Test
    void openCircuitTransitionsHalfOpenAfterWaitDuration() throws InterruptedException {
        ChatClientRequest req = requestForModel("gpt-4o");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("down"));
        tripBreaker(req);
        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "OPEN");

        Thread.sleep(WAIT_OPEN_MS + 100);

        // Next call after the wait window probes HALF_OPEN — a successful response closes the circuit.
        reset(callChain);
        ChatClientResponse ok = mock(ChatClientResponse.class);
        when(callChain.nextCall(any())).thenReturn(ok);

        ChatClientResponse result = advisor.adviseCall(req, callChain);

        assertThat(result).isSameAs(ok);
        verify(callChain, times(1)).nextCall(any());
        // One probe is not enough to close (permitted-calls-half-open=2), so state is HALF_OPEN.
        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "HALF_OPEN");
    }

    @Test
    void nonProviderUnavailableExceptionFromChainPropagatesUnchanged() {
        ChatClientRequest req = requestForModel("gpt-4o");
        RuntimeException boom = new IllegalStateException("kaboom");
        when(callChain.nextCall(any())).thenThrow(boom);

        assertThatThrownBy(() -> advisor.adviseCall(req, callChain))
                .isSameAs(boom);
    }

    @Test
    void getProviderHealthReportsAllTrackedBreakers() {
        ChatClientResponse ok = mock(ChatClientResponse.class);
        when(callChain.nextCall(any())).thenReturn(ok);

        advisor.adviseCall(requestForModel("gpt-4o"), callChain);
        advisor.adviseCall(requestForModel("claude-3-5-sonnet"), callChain);
        advisor.adviseCall(requestForModel("gemini-2.5-pro"), callChain);
        advisor.adviseCall(requestForModel("llama-3.1-70b"), callChain);
        advisor.adviseCall(requestForModel("command-r-plus"), callChain);

        Map<String, String> health = advisor.getProviderHealth();
        assertThat(health).containsKeys(
                "llm-provider:openai",
                "llm-provider:anthropic",
                "llm-provider:google",
                "llm-provider:ollama",
                "llm-provider:cohere");
        assertThat(health.values()).allMatch("CLOSED"::equals);
    }

    @Test
    void streamPathReturnsFluxErrorWhenCircuitOpen() {
        ChatClientRequest req = requestForModel("gpt-4o");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("down"));
        tripBreaker(req);

        Flux<ChatClientResponse> flux = advisor.adviseStream(req, streamChain);

        StepVerifier.create(flux)
                .expectErrorMatches(err -> err instanceof ProviderUnavailableException
                        && "openai".equals(((ProviderUnavailableException) err).getProvider()))
                .verify();
        verify(streamChain, never()).nextStream(any());
    }

    @Test
    void slowCallRateTripsBreakerEvenWhenCallsSucceed() {
        ChatClientRequest req = requestForModel("gpt-4o");
        ChatClientResponse ok = mock(ChatClientResponse.class);
        long slowNanos = TimeUnit.MILLISECONDS.toNanos(SLOW_CALL_DURATION_MS + 10);

        when(callChain.nextCall(any())).thenAnswer(inv -> {
            // Advance the injected clock so the advisor's end - start > slowCallDurationThreshold,
            // causing Resilience4j to record each call as slow without actually sleeping.
            fakeNanos.addAndGet(slowNanos);
            return ok;
        });

        // minCalls=2 is the point at which Resilience4j evaluates the slow-call rate. Two slow
        // successes = 100% slow, over the 80% threshold — breaker opens after the 2nd call
        // (no errors needed).
        for (int i = 0; i < MIN_CALLS; i++) {
            ChatClientResponse result = advisor.adviseCall(req, callChain);
            assertThat(result).isSameAs(ok);
        }

        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "OPEN");
    }

    @Test
    void halfOpenTransitionsToClosedAfterSuccessfulProbes() {
        ChatClientRequest req = requestForModel("gpt-4o");
        when(callChain.nextCall(any())).thenThrow(new RuntimeException("down"));
        tripBreaker(req);
        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "OPEN");

        // Poll for OPEN → HALF_OPEN rather than racing a fixed sleep against Resilience4j's
        // internal clock. Awaitility ensures we retry the probe if Resilience4j hasn't yet
        // flipped the state at the instant of our first check.
        reset(callChain);
        ChatClientResponse ok = mock(ChatClientResponse.class);
        when(callChain.nextCall(any())).thenReturn(ok);

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .pollDelay(Duration.ofMillis(WAIT_OPEN_MS))
                .untilAsserted(() -> {
                    advisor.adviseCall(req, callChain);
                    assertThat(advisor.getProviderHealth())
                            .containsEntry("llm-provider:openai", "HALF_OPEN");
                });

        // Second successful probe completes the permitted-calls-half-open window. With both
        // probes successful (failure rate 0%), the breaker transitions HALF_OPEN → CLOSED.
        ChatClientResponse second = advisor.adviseCall(req, callChain);
        assertThat(second).isSameAs(ok);
        assertThat(advisor.getProviderHealth()).containsEntry("llm-provider:openai", "CLOSED");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void tripBreaker(ChatClientRequest req) {
        for (int i = 0; i < MIN_CALLS; i++) {
            try {
                advisor.adviseCall(req, callChain);
            } catch (RuntimeException expected) {
                // Swallow — we want the side effect (breaker records error).
            }
        }
    }

    private static ChatClientRequest requestForModel(String modelId) {
        ChatClientRequest req = mock(ChatClientRequest.class);
        Prompt prompt = mock(Prompt.class);
        ChatOptions options = mock(ChatOptions.class);
        when(options.getModel()).thenReturn(modelId);
        when(prompt.getOptions()).thenReturn(options);
        when(req.prompt()).thenReturn(prompt);
        return req;
    }
}
