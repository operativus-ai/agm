package ai.operativus.agentmanager.compute.tools.composio;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Provides a singleton WebClient bean for the Composio dynamic tool
 * adapter (R1.2 / N1) with provider-specific timeouts (10s connect / 60s read), an explicit
 * connection pool sized for high-concurrency agent runs, and a 1 MB in-memory cap matched to
 * the adapter's output size cap (avoids Spring's default 256 KB DataBufferLimitException on
 * large but legitimate Composio responses).
 * State: Stateless. Bean is a singleton named "composioWebClient".
 *
 * Per agm-agentos-tool-parity-impl.md §4 + Findings 3, 10, 12 (audit): explicit pool sizing
 * (maxConnections=50, pendingAcquireTimeout=10s) prevents pool-exhaustion stalls under load.
 * Authorization header is set per-request by ComposioToolCallback (NOT defaulted here) since
 * Composio uses X-API-Key, not Authorization: Bearer.
 */
@Configuration
public class ComposioWebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int MAX_IN_MEMORY_BYTES = 1024 * 1024; // 1 MB — matches adapter output cap
    private static final int MAX_CONNECTIONS = 50;
    private static final int PENDING_ACQUIRE_TIMEOUT_SECONDS = 10;

    @Bean(name = "composioWebClient")
    public WebClient composioWebClient(
            @Value("${agent.tools.composio.base-url:https://backend.composio.dev}") String baseUrl) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("composio-pool")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(Duration.ofSeconds(PENDING_ACQUIRE_TIMEOUT_SECONDS))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Singleton {@link CircuitBreaker} shared across all {@code ComposioToolCallback} instances.
     * All Composio failures contribute to the same window so a sustained outage opens the
     * breaker once for the whole adapter, not per-action. Per audit Finding 3 + spec §3 AC.
     *
     * <p>Both <strong>failure-rate</strong> and <strong>slow-call-rate</strong> trip the
     *   breaker. Latency spikes (Composio backend slow-but-healthy) are a real failure mode
     *   that the failure-rate path alone does not catch — slow calls return 200s with
     *   30+ second latencies that quietly burn pool capacity until {@link reactor.netty.resources.ConnectionProvider}
     *   pending-acquire timeouts cascade into actual failures. The slow-call threshold opens
     *   the breaker proactively before that cascade.
     */
    @Bean(name = "composioCircuitBreaker")
    public CircuitBreaker composioCircuitBreaker(
            @Value("${agent.tools.composio.circuit-breaker.failure-rate-threshold:50}") int failureRateThreshold,
            @Value("${agent.tools.composio.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${agent.tools.composio.circuit-breaker.wait-duration-in-open-state-seconds:30}") int waitSeconds,
            @Value("${agent.tools.composio.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
            @Value("${agent.tools.composio.circuit-breaker.slow-call-rate-threshold:80}") int slowCallRateThreshold,
            @Value("${agent.tools.composio.circuit-breaker.slow-call-duration-seconds:30}") int slowCallDurationSeconds) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitSeconds))
                .slidingWindowSize(slidingWindowSize)
                .slowCallRateThreshold(slowCallRateThreshold)
                .slowCallDurationThreshold(Duration.ofSeconds(slowCallDurationSeconds))
                .build();
        return CircuitBreaker.of("composio", config);
    }
}
