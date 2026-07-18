package ai.operativus.agentmanager.integration.mcp;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the auth contract on the inbound MCP server surface
 *   ({@code GET /mcp/sse}, {@code POST /mcp/messages}). Without this pin, a
 *   SecurityConfig regression that silently widened anonymous access on either route
 *   would let a remote MCP client (Claude Desktop / Cline / Cursor) connect, enumerate
 *   all tenant agents via {@code tools/list}, and invoke any of them via {@code run_*}
 *   — a fully unauthenticated multi-tenant LLM-execution surface.
 *
 *   <p>Three pins:
 *   <ol>
 *     <li>Anonymous {@code GET /mcp/sse} → 401 or 403 (no session, no endpoint event).</li>
 *     <li>Anonymous {@code POST /mcp/messages} (initialize) → 401 or 403; no JSON-RPC
 *         response envelope returned (would imply the controller ran the dispatch).</li>
 *     <li>Anonymous {@code POST /mcp/messages} (tools/list) → 401 or 403; the tool list
 *         must NOT be enumerable without authentication.</li>
 *   </ol>
 *
 *   <p>Sibling to {@code RunsAuthContractRuntimeTest} for the chat surface — same auth
 *   contract family, different controller.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class McpInboundAuthContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void installPermissiveErrorHandler() {
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
    }

    @Test
    void anonymousGetSseHandshake_returnsUnauthorizedOrForbidden_noEndpointEventLeak() {
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        ResponseEntity<String> resp = rest.exchange(
                url("/mcp/sse"), HttpMethod.GET, new HttpEntity<>(noAuth), String.class);

        HttpStatus status = HttpStatus.resolve(resp.getStatusCode().value());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous GET /mcp/sse must be 401 or 403 — anything else (200, 404, 500) "
                        + "means the SSE handler ran for an unauthenticated caller. Actual: " + status);

        String body = resp.getBody();
        // If the handler returned 200 with an SSE body, the FE would see `data:/mcp/messages?sessionId=...`
        // and obtain a usable session — that's the leak we're guarding against.
        assertTrue(body == null || !body.contains("data:/mcp/messages?sessionId="),
                "rejected SSE handshake must NOT leak a sessionId endpoint event in the body. "
                        + "A leak here means an anonymous caller can subsequently POST /mcp/messages "
                        + "with that sessionId and invoke agents. Body: " + body);
    }

    @Test
    void anonymousPostMessagesInitialize_returnsUnauthorizedOrForbidden_noJsonRpcResponse() {
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);

        String reqId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "initialize",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, noAuth), JSON_MAP);

        HttpStatus status = HttpStatus.resolve(resp.getStatusCode().value());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous POST /mcp/messages must be 401 or 403 before the JSON-RPC dispatch "
                        + "runs. A 200 here means the controller produced a result envelope for an "
                        + "unauthenticated caller — fully unauthenticated server access. Actual: " + status);

        // If the body is parseable, it must NOT be a successful JSON-RPC response.
        Map<String, Object> respBody = resp.getBody();
        if (respBody != null && respBody.containsKey("result")) {
            throw new AssertionError(
                    "anonymous POST returned a JSON-RPC result envelope — the dispatch ran. Body: " + respBody);
        }
    }

    @Test
    void anonymousPostMessagesToolsList_returnsUnauthorizedOrForbidden_noToolEnumerationLeak() {
        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);

        String reqId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "tools/list",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, noAuth), JSON_MAP);

        HttpStatus status = HttpStatus.resolve(resp.getStatusCode().value());
        assertNotNull(status);
        assertTrue(status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN,
                "anonymous tools/list must be 401 or 403 — a 200 here would expose every "
                        + "tenant's agent IDs (via run_<agentId> entries) to unauthenticated remote "
                        + "callers. Actual: " + status);

        Map<String, Object> respBody = resp.getBody();
        if (respBody != null && respBody.get("result") instanceof Map<?, ?> result
                && result.get("tools") instanceof java.util.List<?> tools
                && !tools.isEmpty()) {
            throw new AssertionError(
                    "anonymous tools/list leaked the dynamic tool list. Tools: " + tools);
        }
    }
}
