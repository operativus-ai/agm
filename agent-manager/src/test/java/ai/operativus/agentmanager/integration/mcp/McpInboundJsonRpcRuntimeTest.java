package ai.operativus.agentmanager.integration.mcp;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the INBOUND MCP server surface
 *   ({@code McpController} at {@code /mcp/*}), which exposes AgentManager AS an MCP
 *   server to external clients (Claude Desktop, Cline, Cursor). This is JSON-RPC 2.0
 *   over SSE+POST: clients connect to {@code GET /mcp/sse} to obtain a per-session
 *   messages endpoint, then POST JSON-RPC envelopes to {@code /mcp/messages}.
 *
 *   Complements {@link McpServersRuntimeTest} which covers the OUTBOUND client pool
 *   ({@code McpConnectionPool}) for the opposite direction (AgentManager AS an MCP
 *   client of other servers).
 *
 *   Tested envelope methods: {@code initialize}, {@code tools/list}, {@code tools/call}
 *   (system tool {@code list_agents}, dynamic per-agent tool {@code run_<agentId>}).
 *   Tested error envelopes: {@code -32600 Invalid Request} (missing jsonrpc/method),
 *   {@code -32601 Method not found} (unknown method, unknown tool). Notification
 *   semantics: a JSON-RPC message without {@code id} is a notification and the
 *   handler returns no response body.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
         NoOpReflectionServiceConfig.class})
public class McpInboundJsonRpcRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    // ─── §17 Case 1 — GET /mcp/sse handshake ─────────────────────────────────

    @Test
    void getSseHandshake_sendsEndpointEventWithSessionId() throws Exception {
        HttpHeaders auth = authenticatedHeaders("mcp-sse-handshake");
        String token = auth.getFirst(HttpHeaders.AUTHORIZATION);

        // McpController.connect() configures SseEmitter(Long.MAX_VALUE) and writes the
        // first event synchronously before returning. We use a raw HttpURLConnection +
        // read-timeout instead of TestRestTemplate.exchange because the latter would
        // block until the emitter closes (it never does on its own).
        HttpURLConnection conn = (HttpURLConnection) URI.create(url("/mcp/sse")).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        conn.setRequestProperty(HttpHeaders.AUTHORIZATION, token);
        conn.setReadTimeout(3000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(200, conn.getResponseCode(),
                    "GET /mcp/sse must return 200 on the SSE upgrade — a 401 here would mean the route gained an auth-only check that excludes Bearer tokens; a 404 would mean the controller mapping moved");

            StringBuilder firstEvent = new StringBuilder();
            String line;
            int safety = 0;
            while ((line = r.readLine()) != null && safety++ < 16) {
                if (line.isEmpty()) break;
                firstEvent.append(line).append('\n');
            }
            String event = firstEvent.toString();
            assertTrue(event.contains("event:endpoint"),
                    "first SSE event must declare event:endpoint per the MCP handshake spec; got: " + event);
            assertTrue(event.contains("data:/mcp/messages?sessionId="),
                    "endpoint data must point external clients at the sessionId-bound POST surface; got: " + event);
        } finally {
            conn.disconnect();
        }
    }

    // ─── §17 Case 2 — POST /mcp/messages initialize ──────────────────────────

    @Test
    void initialize_returnsProtocolVersionAndServerIdentification() {
        HttpHeaders auth = authenticatedHeaders("mcp-initialize");
        String reqId = UUID.randomUUID().toString();

        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "initialize",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("2.0", body.get("jsonrpc"));
        assertEquals(reqId, body.get("id"), "response id must echo the request id per JSON-RPC §5");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertNotNull(result, "initialize must return a result body");
        assertEquals("0.1.0", result.get("protocolVersion"),
                "protocolVersion is hard-coded in McpController.handleMessage; a different value here means a deliberate spec upgrade");
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) result.get("server");
        assertNotNull(server);
        assertEquals("AgentManager", server.get("name"),
                "server.name identifies this instance to external MCP clients; renaming would break Claude Desktop / Cline / Cursor configs");
    }

    // ─── §17 Case 3 — tools/list includes list_agents + per-agent run_X ──────

    @Test
    void toolsList_includesSystemListAgentsToolAndOneRunToolPerActiveAgent() {
        HttpHeaders auth = authenticatedHeaders("mcp-tools-list");
        String agentId = createAgent(auth, "MCP Tools List Probe");

        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "tools-list-1",
                "method", "tools/list");

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertNotNull(tools);

        boolean hasListAgents = tools.stream().anyMatch(t -> "list_agents".equals(t.get("name")));
        boolean hasRunForAgent = tools.stream().anyMatch(t -> ("run_" + agentId).equals(t.get("name")));
        assertTrue(hasListAgents, "tools list must include the system list_agents tool; got " + tools);
        assertTrue(hasRunForAgent,
                "tools list must include run_<agentId> for every active agent visible to the caller's org; "
                        + "missing run_" + agentId + " means AgentRegistry.findAll dropped the agent, "
                        + "AgentContextHolder.getOrgId() didn't propagate, or the dynamic tool synthesis loop "
                        + "in McpController.getDynamicToolsList regressed. Got: " + tools);
    }

    // ─── §17 Case 4 — tools/call list_agents ─────────────────────────────────

    @Test
    void toolsCallListAgents_returnsTextContentWithAgentEnumeration() {
        HttpHeaders auth = authenticatedHeaders("mcp-tools-call-list");
        String agentId = createAgent(auth, "MCP List-Agents Tool Probe");

        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "list-call-1",
                "method", "tools/call",
                "params", Map.of("name", "list_agents", "arguments", Map.of()));

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertNotNull(content);
        assertEquals(1, content.size(), "list_agents must return exactly one text content block");
        Map<String, Object> block = content.get(0);
        assertEquals("text", block.get("type"));
        String text = (String) block.get("text");
        assertNotNull(text);
        assertTrue(text.contains(agentId),
                "list_agents text must enumerate the seeded agentId; missing means McpOperations.list_agents "
                        + "is no longer reading from AgentRegistry or the org context isn't propagating. Got: " + text);
    }

    // ─── §17 Case 5 — tools/call run_<agentId> ───────────────────────────────

    @Test
    void toolsCallRunAgent_invokesAgentAndReturnsModelResponseText() {
        HttpHeaders auth = authenticatedHeaders("mcp-tools-call-run");
        String agentId = createAgent(auth, "MCP Run-Agent Tool Probe");
        String scriptedResponse = "mcp-run-agent-scripted-" + UUID.randomUUID();
        fakeModel.respondWith(scriptedResponse);

        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "run-call-1",
                "method", "tools/call",
                "params", Map.of(
                        "name", "run_" + agentId,
                        "arguments", Map.of("message", "Hello via MCP")));

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.getBody().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("text", content.get(0).get("type"));
        assertEquals(scriptedResponse, content.get(0).get("text"),
                "run_<agentId> must dispatch through AgentControlService.run_agent → AgentService.run and "
                        + "return the model's response text. A different value here would mean the wire to "
                        + "AgentService broke or the FakeChatModel response wasn't routed back into the MCP envelope");
    }

    // ─── §17 Case 6 — missing jsonrpc field → -32600 ─────────────────────────

    @Test
    void missingJsonrpcField_returnsInvalidRequestEnvelope() {
        HttpHeaders auth = authenticatedHeaders("mcp-err-no-jsonrpc");
        Map<String, Object> envelope = Map.of(
                "id", "bad-1",
                "method", "initialize");

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "JSON-RPC errors travel inside the response envelope, not as HTTP 4xx — the transport is still 200");
        assertJsonRpcErrorCode(resp.getBody(), -32600);
        assertEquals("bad-1", resp.getBody().get("id"),
                "even error envelopes must echo the request id so clients can correlate failures");
    }

    // ─── §17 Case 7 — missing method field → -32600 ──────────────────────────

    @Test
    void missingMethodField_returnsInvalidRequestEnvelope() {
        HttpHeaders auth = authenticatedHeaders("mcp-err-no-method");
        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "bad-2");

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertJsonRpcErrorCode(resp.getBody(), -32600);
    }

    // ─── §17 Case 8 — unknown method → -32601 ────────────────────────────────

    @Test
    void unknownMethod_returnsMethodNotFoundEnvelope() {
        HttpHeaders auth = authenticatedHeaders("mcp-err-unknown-method");
        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "unknown-method-1",
                "method", "prompts/list");

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertJsonRpcErrorCode(resp.getBody(), -32601);
    }

    // ─── §17 Case 9 — unknown tool → -32601 ──────────────────────────────────

    @Test
    void unknownTool_returnsMethodNotFoundEnvelope() {
        HttpHeaders auth = authenticatedHeaders("mcp-err-unknown-tool");
        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "id", "unknown-tool-1",
                "method", "tools/call",
                "params", Map.of(
                        "name", "tool-does-not-exist-" + UUID.randomUUID(),
                        "arguments", Map.of()));

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertJsonRpcErrorCode(resp.getBody(), -32601);
    }

    // ─── §17 Case 10 — notification (no id) → null body ──────────────────────

    @Test
    void notificationWithoutId_returnsNoResponseBody() {
        HttpHeaders auth = authenticatedHeaders("mcp-notification");
        Map<String, Object> envelope = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized");

        ResponseEntity<Map<String, Object>> resp = rpcExchange(auth, envelope);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "notifications still get HTTP 200 — JSON-RPC notification semantics live inside the body shape");
        assertNull(resp.getBody(),
                "notification (no id field) must return null body per McpController.result/error early-return; "
                        + "a non-null body here would mean the controller started fabricating a response for "
                        + "notification senders, which would confuse compliant JSON-RPC clients");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> rpcExchange(HttpHeaders auth, Map<String, Object> envelope) {
        return rest.exchange(
                url("/mcp/messages"),
                HttpMethod.POST, new HttpEntity<>(envelope, auth), JSON_MAP);
    }

    private void assertJsonRpcErrorCode(Map<String, Object> body, int expectedCode) {
        assertNotNull(body, "JSON-RPC error envelope must have a body");
        assertEquals("2.0", body.get("jsonrpc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertNotNull(error, "JSON-RPC error envelope must carry an `error` object; got body=" + body);
        assertEquals(expectedCode, ((Number) error.get("code")).intValue(),
                "JSON-RPC error code must be " + expectedCode + " per spec mapping in McpController; got error=" + error);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-mcp-rpc-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T040 MCP inbound JSON-RPC fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "agent fixture precondition; got " + resp.getStatusCode());
        return agentId;
    }
}
