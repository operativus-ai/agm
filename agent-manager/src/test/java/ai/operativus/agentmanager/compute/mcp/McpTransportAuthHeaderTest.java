package ai.operativus.agentmanager.compute.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.core.model.McpTransport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Proves the gap that {@link McpConnectionPoolTest} cannot: that the bearer-auth
 * {@code httpRequestCustomizer} attached by {@link McpConnectionPool#buildTransport} actually
 * puts {@code Authorization: Bearer <token>} on the OUTBOUND request — not merely that the
 * lambda constructs without throwing.
 *
 * <p>This drives {@code buildTransport} directly against an in-process WireMock server rather
 * than through {@link McpConnectionPool#connect}, because the pool's runtime SSRF backstop
 * ({@code SsrfGuard.validate(url, false)}) hard-rejects the loopback URL WireMock binds to.
 * We don't care whether the MCP handshake completes — WireMock records the request (and its
 * headers) on receipt, so a bounded {@code initialize()} attempt is enough to capture it.</p>
 *
 * <p>Covers the {@code STREAMABLE_HTTP} path (the hosted-GitHub-MCP shape) and the SSE path,
 * plus the negative case (no auth configured ⇒ no Authorization header).</p>
 */
class McpTransportAuthHeaderTest {

    private static final String TOKEN = "ghp_test_token_abcd1234";

    private WireMockServer wiremock;

    @BeforeEach
    void startServer() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
        // Any method / any path → 200 with a trivial body. The handshake will fail to parse this
        // as a valid MCP response (we catch that); the point is only that the request is sent.
        wiremock.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));
    }

    @AfterEach
    void stopServer() {
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    @Test
    void streamableHttp_withAuth_sendsBearerHeaderOnTheWire() {
        attemptInitialize(McpTransport.STREAMABLE_HTTP, wiremock.baseUrl() + "/mcp", TOKEN);

        wiremock.verify(anyRequestedFor(anyUrl())
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void sse_withAuth_sendsBearerHeaderOnTheWire() {
        attemptInitialize(McpTransport.SSE, wiremock.baseUrl(), TOKEN);

        wiremock.verify(anyRequestedFor(anyUrl())
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void streamableHttp_withoutAuth_sendsNoAuthorizationHeader() {
        attemptInitialize(McpTransport.STREAMABLE_HTTP, wiremock.baseUrl() + "/mcp", null);

        // Zero requests may carry an Authorization header when no auth secret is configured.
        wiremock.verify(0, anyRequestedFor(anyUrl())
                .withHeader("Authorization", matching(".*")));
    }

    /**
     * Builds the transport via the production seam and drives a bounded {@code initialize()} so
     * the first request reaches WireMock. The handshake is expected to fail (the stub is not a
     * real MCP server); we swallow that — the assertion is on what WireMock received.
     */
    private void attemptInitialize(McpTransport transport, String url, String auth) {
        McpClientTransport mcpTransport = McpConnectionPool.buildTransport(transport, url, auth);
        var client = McpClient.sync(mcpTransport).build();
        try {
            // The outbound request hits WireMock in milliseconds; this bound only caps how long
            // we wait for the (doomed) handshake before asserting on what was received.
            CompletableFuture.runAsync(client::initialize).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Handshake failure / timeout is fine — the outbound request already hit WireMock.
        } finally {
            try { client.closeGracefully(); } catch (Exception ignored) { /* best effort */ }
        }
    }
}
