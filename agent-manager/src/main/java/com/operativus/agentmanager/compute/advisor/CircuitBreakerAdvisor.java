package com.operativus.agentmanager.compute.advisor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Wraps LLM calls in a per-provider Resilience4j CircuitBreaker.
 * When a provider fails repeatedly (default: 50% failure rate over 10-call sliding window),
 * the circuit opens and subsequent calls fail fast (default: 30s) before half-opening.
 *
 * This prevents cascading failures when an LLM provider is down and enables
 * fast detection for fallback routing.
 *
 * All Resilience4j thresholds are externalised via {@code agent.guardrails.circuit-breaker.*}
 * so tests can dial {@code wait-duration-open-ms} down to sub-second values without forking
 * the bean, and operators can tune production values without a redeploy.
 */
@Component
public class CircuitBreakerAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerAdvisor.class);

    private final CircuitBreakerRegistry registry;
    private final LongSupplier nanoClock;
    /** §2 advisor-chain decomposition: per-advisor processing-time timer, tag {@code advisor=circuit_breaker}. */
    private final Timer durationTimer;

    @Autowired
    public CircuitBreakerAdvisor(
            @Value("${agent.guardrails.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${agent.guardrails.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${agent.guardrails.circuit-breaker.minimum-number-of-calls:3}") int minimumNumberOfCalls,
            @Value("${agent.guardrails.circuit-breaker.wait-duration-open-ms:30000}") long waitDurationOpenMs,
            @Value("${agent.guardrails.circuit-breaker.permitted-calls-half-open:2}") int permittedCallsHalfOpen,
            @Value("${agent.guardrails.circuit-breaker.slow-call-duration-ms:60000}") long slowCallDurationMs,
            @Value("${agent.guardrails.circuit-breaker.slow-call-rate-threshold:80}") float slowCallRateThreshold,
            MeterRegistry meterRegistry) {
        this(failureRateThreshold, slidingWindowSize, minimumNumberOfCalls, waitDurationOpenMs,
                permittedCallsHalfOpen, slowCallDurationMs, slowCallRateThreshold, System::nanoTime, meterRegistry);
    }

    /**
     * Test-only constructor. Allows the call-duration clock to be substituted so slow-call
     * behavior can be pinned deterministically without {@code Thread.sleep(slowCallDurationMs)}
     * per call. The provided supplier must return monotonic nanoseconds; the advisor computes
     * {@code end - start} in nanoseconds and reports it to Resilience4j via
     * {@code cb.onSuccess / cb.onError} in {@link TimeUnit#NANOSECONDS}.
     *
     * <p>Note: this clock does NOT influence Resilience4j's internal OPEN → HALF_OPEN wait
     * timer — that still uses Resilience4j's own clock. It only controls the duration fed into
     * success/error records, which is what drives the slow-call rate calculation.</p>
     */
    public CircuitBreakerAdvisor(
            float failureRateThreshold,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            long waitDurationOpenMs,
            int permittedCallsHalfOpen,
            long slowCallDurationMs,
            float slowCallRateThreshold,
            LongSupplier nanoClock,
            MeterRegistry meterRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationOpenMs))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsHalfOpen)
                .slowCallDurationThreshold(Duration.ofMillis(slowCallDurationMs))
                .slowCallRateThreshold(slowCallRateThreshold)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
        this.nanoClock = nanoClock;
        this.durationTimer = Timer.builder("advisor.duration_ms")
                .tag("advisor", "circuit_breaker").register(meterRegistry);
    }

    @Override
    public String getName() {
        return "CircuitBreakerAdvisor";
    }

    @Override
    public int getOrder() {
        return 1; // Run early — fail fast before other advisors process
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return durationTimer.record(() -> {
            String provider = resolveProvider(request);
            CircuitBreaker cb = registry.circuitBreaker("llm-provider:" + provider);

            if (!cb.tryAcquirePermission()) {
                log.warn("Circuit OPEN for provider '{}'. Failing fast. State: {}", provider, cb.getState());
                throw new ProviderUnavailableException(provider, "Circuit breaker is open — provider has been failing. Try again shortly.");
            }

            long start = nanoClock.getAsLong();
            try {
                ChatClientResponse response = chain.nextCall(request);
                cb.onSuccess(nanoClock.getAsLong() - start, TimeUnit.NANOSECONDS);
                return response;
            } catch (Exception e) {
                cb.onError(nanoClock.getAsLong() - start, TimeUnit.NANOSECONDS, e);
                throw e;
            }
        });
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String provider = resolveProvider(request);
        CircuitBreaker cb = registry.circuitBreaker("llm-provider:" + provider);

        if (!cb.tryAcquirePermission()) {
            log.warn("Circuit OPEN for provider '{}'. Failing fast on stream.", provider);
            return Flux.error(new ProviderUnavailableException(provider, "Circuit breaker is open."));
        }
        long start = nanoClock.getAsLong();
        return chain.nextStream(request)
                .doOnComplete(() -> cb.onSuccess(nanoClock.getAsLong() - start, TimeUnit.NANOSECONDS))
                .doOnError(e -> cb.onError(nanoClock.getAsLong() - start, TimeUnit.NANOSECONDS, e));
    }

    /**
     * Returns the circuit breaker health status for all tracked providers.
     */
    public Map<String, String> getProviderHealth() {
        Map<String, String> health = new LinkedHashMap<>();
        registry.getAllCircuitBreakers().forEach(cb ->
                health.put(cb.getName(), cb.getState().toString()));
        return health;
    }

    /**
     * Extracts the provider from the model name in the request's ChatOptions.
     * Groups circuit breakers by provider prefix (e.g. "openai", "anthropic", "google")
     * so that a single provider outage doesn't trip breakers for other providers.
     */
    private String resolveProvider(ChatClientRequest request) {
        try {
            var options = request.prompt().getOptions();
            if (options instanceof org.springframework.ai.chat.prompt.ChatOptions chatOptions) {
                String model = chatOptions.getModel();
                if (model != null && !model.isBlank()) {
                    return extractProvider(model);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract provider from request, falling back to default", e);
        }
        return "default";
    }

    /**
     * Maps a model identifier to its provider name for circuit breaker grouping.
     */
    private static String extractProvider(String modelId) {
        String lower = modelId.toLowerCase();
        if (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")) return "openai";
        if (lower.startsWith("claude-")) return "anthropic";
        if (lower.startsWith("gemini-") || lower.startsWith("gemma-")) return "google";
        if (lower.startsWith("llama") || lower.startsWith("mistral") || lower.startsWith("mixtral") || lower.startsWith("codestral")) return "ollama";
        if (lower.startsWith("command-")) return "cohere";
        // Fall back to using the model prefix before the first dash as the provider key
        int dash = lower.indexOf('-');
        return dash > 0 ? lower.substring(0, dash) : lower;
    }

    /**
     * Thrown when a provider's circuit breaker is open.
     */
    public static class ProviderUnavailableException extends RuntimeException {
        private final String provider;

        public ProviderUnavailableException(String provider, String message) {
            super(message);
            this.provider = provider;
        }

        public String getProvider() {
            return provider;
        }
    }
}
