package com.operativus.agentmanager.integration.mcp;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Extends {@code McpInboundJsonRpcRuntimeTest} with the
 *   error-envelope cases not pinned there. The existing test covers -32600 (Invalid
 *   Request) and -32601 (Method not found); this one pins:
 *   <ul>
 *     <li>-32700 Parse Error — malformed JSON body. {@code id} must be {@code null}
 *         per the spec because no parse means no id is knowable.</li>
 *     <li>Notification semantics — a JSON-RPC message with no {@code id} field must
 *         not produce a response body (Spring should return 200 with empty body).</li>
 *     <li>Id round-trip — string and integer id types must echo verbatim (no Jackson
 *         JsonNode property-map serialization regression).</li>
 *     <li>HTTP 200 invariant — every JSON-RPC error rides INSIDE the envelope. The
 *         transport status code stays 200; clients dispatch by parsing the body's
 *         {@code error.code} field.</li>
 *   </ul>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class McpInboundJsonRpcErrorShapeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void postMalformedJsonBody_returns200WithParseErrorAndNullId() {
        HttpHeaders auth = authHeaders("mcp-err-parse");

        // Deliberately broken JSON. The controller's objectMapper.readTree throws and
        // the handler returns a -32700 envelope with id=null per spec.
        String malformed = "{ this is not valid json ";

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(malformed, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "JSON-RPC parse error rides inside the envelope — HTTP transport must stay 200");

        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("2.0", body.get("jsonrpc"));
        assertTrue(body.containsKey("id"), "spec: id field must be present even on parse error");
        assertNull(body.get("id"),
                "spec: id must be null on parse error — no parse means no id known");

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertNotNull(error, "parse-error response must carry an error object");
        assertEquals(-32700, ((Number) error.get("code")).intValue(),
                "parse error uses code -32700 per JSON-RPC 2.0 spec");
    }

    @Test
    void notificationWithoutId_returns200WithEmptyBody_noResponseEnvelope() {
        HttpHeaders auth = authHeaders("mcp-notif");

        // Notification: a JSON-RPC message with no `id` field. Per spec the server
        // must NOT send a response. The controller returns null which Spring serializes
        // as 200 with empty body.
        Map<String, Object> notification = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized");

        ResponseEntity<String> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(notification, auth), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "notification handling returns 200 even without a response body");

        String body = resp.getBody();
        assertTrue(body == null || body.isBlank(),
                "notifications must NOT produce a response envelope per JSON-RPC 2.0 §4.1. "
                        + "A non-empty body here would mean the handler accidentally echoed a "
                        + "result/error envelope for a notification — clients ignore the body "
                        + "but bandwidth + log noise. Body: " + body);
    }

    @Test
    void requestWithStringId_responseEchoesStringIdNotJsonNodePropertyMap() {
        HttpHeaders auth = authHeaders("mcp-id-string");

        String reqId = "string-id-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "initialize",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        Object echoedId = respBody.get("id");
        assertEquals(reqId, echoedId,
                "string id must round-trip verbatim. If you see a property-map like "
                        + "{array=false, textual=true, ...} here, McpController.unwrapId regressed "
                        + "and Jackson is serializing the JsonNode bean instead of the underlying "
                        + "string — every JSON-RPC client correlation would break.");
    }

    @Test
    void requestWithIntegerId_responseEchoesIntegerIdAsNumber() {
        HttpHeaders auth = authHeaders("mcp-id-int");

        long reqId = 42L;
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "initialize",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        Object echoedId = respBody.get("id");
        assertTrue(echoedId instanceof Number,
                "integer id must serialize as a JSON number, not a string. Got: "
                        + echoedId + " (" + (echoedId == null ? "null" : echoedId.getClass().getName()) + ")");
        assertEquals(reqId, ((Number) echoedId).longValue(),
                "integer id must round-trip its value");
    }

    @Test
    void requestForUnknownMethod_returns200WithMethodNotFoundError() {
        // Regression-lock: -32601 envelope shape (method-not-found is the most-common
        // error a misconfigured client would see). McpInboundJsonRpcRuntimeTest already
        // covers one missing-method case; this one pins the exact code + presence of
        // the offending method name in the error message for FE diagnostics.
        HttpHeaders auth = authHeaders("mcp-unknown-method");

        String reqId = UUID.randomUUID().toString();
        String madeUpMethod = "fakeMethod_" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", madeUpMethod,
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        assertEquals(reqId, respBody.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) respBody.get("error");
        assertNotNull(error, "unknown-method response must carry an error object");
        assertEquals(-32601, ((Number) error.get("code")).intValue(),
                "unknown method uses code -32601 per JSON-RPC 2.0 spec");
        String message = (String) error.get("message");
        assertNotNull(message);
        assertTrue(message.contains(madeUpMethod),
                "error.message must include the offending method name so the FE can show a "
                        + "useful diagnostic. Got: " + message);
    }

    // ─── helpers ───

    private HttpHeaders authHeaders(String username) {
        HttpHeaders auth = authenticateAs(username, username + "@test.local",
                "pass-mcp-err-1234", List.of("ROLE_USER"));
        auth.setContentType(MediaType.APPLICATION_JSON);
        return auth;
    }
}
