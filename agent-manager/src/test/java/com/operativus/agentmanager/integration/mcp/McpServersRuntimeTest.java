package com.operativus.agentmanager.integration.mcp;

import com.operativus.agentmanager.compute.mcp.McpConnectionPool;
import com.operativus.agentmanager.control.repository.ExtensionRegistrationRepository;
import com.operativus.agentmanager.core.entity.ExtensionRegistrationEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the MCP (Model Context Protocol)
 *   surface — {@link com.operativus.agentmanager.compute.mcp.McpConnectionPool} (the
 *   {@code @EventListener(ApplicationReadyEvent.class)} reconciler at line 53, the public
 *   {@code connect/disconnect} entrypoints, and the @{@link jakarta.annotation.PreDestroy}
 *   shutdown path), the {@code extensions} table view that the pool reads on startup, and
 *   the cluster-sync wiring via {@code McpClusterEventListener} (Redis PubSub channels
 *   {@code extension:registered} / {@code extension:deleted}). Pins:
 *     - empty-state startup contract,
 *     - that the API-driven registration path delegates connect to the cluster-event listener
 *       rather than calling the pool inline (architectural pin, not a defect),
 *     - the fail-soft contract on transport failure (the pool reserves the key with an
 *       empty tool list rather than letting the exception bubble),
 *     - that a broken MCP registration cannot turn an agent run into a 500,
 *     - the disconnect contract (pool entry removed; subsequent disconnects are no-ops),
 *   plus two {@code @Disabled} placeholders that document spec-vs-reality drift.
 * State: Stateless test class. The {@code McpConnectionPool} bean ITSELF is stateful and
 *   shared across the whole JVM — see {@link #resetPool()} for per-test cleanup.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §17 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T040 (6 cases).
 *
 * Connection seam — why direct pool calls instead of the controller:
 *   {@link com.operativus.agentmanager.control.controller.ExtensionController#registerExtension}
 *   does NOT call {@code McpConnectionPool.connect(...)} directly. For MCP-typed extensions
 *   it publishes {@code extension:registered} on Redis (line 71); the actual connect is
 *   driven by {@link com.operativus.agentmanager.compute.mcp.McpClusterEventListener#registrationListener}
 *   which subscribes to that channel and calls {@code connectionPool.connect(id)}.
 *   The test profile has no Redis container ({@code spring.cache.type=none},
 *   {@code spring.data.redis.repositories.enabled=false} in {@code application-test.properties}),
 *   so calling the controller for MCP would attempt to publish to a missing Redis and throw.
 *   We therefore drive the seam directly: persist the {@code extensions} row via repository
 *   and call {@code mcpConnectionPool.connect(id)} ourselves — the same effective
 *   sequence that the cluster listener performs in production.
 *
 * Why use {@code http://localhost:1/sse} as the MCP server URL:
 *   {@code McpConnectionPool.connect} catches all exceptions and stores an empty
 *   {@code ToolCallback} list against the extension id (line 100-103) — this is the
 *   fail-soft contract we want to pin. Port 1 refuses TCP immediately on every supported
 *   platform, so the SSE handshake throws {@code ConnectException} instantly rather than
 *   blocking on a default 20-second initialise timeout. A real MCP server fixture would
 *   need to fake the full SSE → MCP-protocol bidirectional dance (initialize, tools/list,
 *   tools/call), which is materially heavier than what a pgvector-backed integration test
 *   should own. See spec-aligned defer notes on cases (4) and (6).
 *
 * Implementation notes / gaps these tests pin:
 *   - Spec §17.1 names {@code spring.ai.mcp.client.stdio.connections.*} as the source of
 *     startup-configured MCPs. The current pool does NOT read those properties at all;
 *     the only source of startup MCPs is the {@code extensions} table filtered for
 *     {@code type='MCP' AND active=true} (line 56-58). Empty-state assertion is therefore
 *     "no DB rows → empty pool" rather than "no property keys → empty pool".
 *   - Spec §17.5 says the removal path "closes the stdio process cleanly (no zombies)".
 *     Production transport is {@code HttpClientSseClientTransport} (HTTP/SSE) — there is
 *     no child stdio process. Case 5 pins the as-shipped HTTP-transport disconnect contract
 *     and notes the divergence inline.
 *   - Spec §17.6 expects a circuit breaker around slow tool invocation. {@code McpConnectionPool}
 *     has no Resilience4j {@code @CircuitBreaker} / {@code @Retry} / {@code @TimeLimiter}
 *     wrapping {@code connect()} or the per-tool dispatch. The fail-soft empty-list-on-error
 *     behaviour is the closest current contract; case 6 is {@code @Disabled} with rationale.
 *   - Spec §17.4 expects MCP tool invocation to surface in {@code ToolCallDTO} + audit log.
 *     This requires a real MCP server that completes the SSE handshake AND declares at
 *     least one callable tool. {@code @Disabled} until a {@code TestMcpServer} support
 *     class lands.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class McpServersRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private McpConnectionPool mcpConnectionPool;
    @Autowired private ExtensionRegistrationRepository extensionRepository;
    @Autowired private FakeChatModel fakeModel;

    /**
     * The {@link McpConnectionPool} bean is created once per JVM and its
     * {@code activeConnections} map is NOT touched by the per-test
     * {@link BaseIntegrationTest#truncateDatabase()} (which only wipes SQL tables).
     * Without this reset, anything a prior test left in the pool would leak into
     * the empty-state assertion of case 1 and the disconnect assertions of case 5.
     */
    @BeforeEach
    void resetPool() {
        new HashSet<>(mcpConnectionPool.getConnectedExtensionIds())
                .forEach(mcpConnectionPool::disconnect);
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    // ─── §17 Case 1 — startup reconciliation with no active MCP rows ───

    /**
     * Spec §17.1. After truncation + per-test pool reset, the pool MUST be empty —
     *   reconciliation is a no-op when no {@code extensions} row matches
     *   {@code type='MCP' AND active=true}. Pin: empty {@code activeConnections} and
     *   empty flattened {@code getAllToolCallbacks}; the @{@code EventListener} method
     *   has already fired exactly once at {@code ApplicationReadyEvent} (Spring boot
     *   the context once for the JVM), and re-running it here would be a duplicate
     *   reconciliation. Empty-state at the steady-state IS the assertion.
     */
    @Test
    void startupReconciliation_withNoActiveMcpExtensions_poolStaysEmpty() {
        assertTrue(mcpConnectionPool.getConnectedExtensionIds().isEmpty(),
                "pool must be empty when no MCP extensions are active in the registry");
        assertTrue(mcpConnectionPool.getAllToolCallbacks().isEmpty(),
                "no tool callbacks should be exposed when no MCP servers are connected");
    }

    // ─── §17 Case 2 — registration round-trip + connect populates pool ───

    /**
     * Spec §17.2. Adding an MCP extension via the registry should make tools available to
     *   agents. Two distinct hops in production:
     *     (a) {@code ExtensionController.POST /api/v1/extensions} writes the {@code extensions}
     *         row and publishes {@code extension:registered} on Redis,
     *     (b) {@code McpClusterEventListener#registrationListener} subscribes to that channel
     *         and calls {@code McpConnectionPool.connect(id)} on every cluster node
     *         (including the originating node — Spring Data Redis publishes-to-self).
     *   We exercise hop (b) directly because the test profile has no Redis container.
     *
     *   Pin (architectural, not defect): the controller does NOT inline-call the pool, so
     *   inserting the row WITHOUT firing the connect call leaves the pool empty. This is
     *   intentional — the cluster listener owns connect — but worth pinning so a future
     *   refactor that bypasses the listener gets caught here.
     */
    @Test
    void registerMcpExtension_connectInvocationPopulatesPoolEntry_apiPathDoesNotInlineConnect() {
        String extId = "mcp-add-" + UUID.randomUUID();
        String url = "http://localhost:1/sse";

        ExtensionRegistrationEntity entity = new ExtensionRegistrationEntity();
        entity.setId(extId);
        entity.setName("T040 add probe");
        entity.setType("MCP");
        entity.setUrl(url);
        entity.setActive(true);
        entity.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        extensionRepository.save(entity);

        assertFalse(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "ARCHITECTURAL PIN: persisting the extensions row alone does NOT populate the pool — "
                        + "ExtensionController publishes extension:registered on Redis and "
                        + "McpClusterEventListener.registrationListener owns the connect() call.");

        mcpConnectionPool.connect(extId);

        assertTrue(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "after connect(), the pool must reserve the extension id (line 96/102)");
        List<ToolCallback> tools = mcpConnectionPool.getToolCallbacks(extId);
        assertNotNull(tools, "getToolCallbacks must never return null (Collections.emptyList fallback)");
    }

    // ─── §17 Case 3 — failed health check / unreachable server is fail-soft ───

    /**
     * Spec §17.3. An MCP server that fails the connection / health check must be excluded
     *   from routing — and CRUCIALLY, agent runs that don't reference MCP tools must still
     *   complete normally with no 500. {@code McpConnectionPool.connect} catches the
     *   {@code ConnectException} from the SSE transport against {@code localhost:1} and
     *   stores an empty tool list against the extension id (line 102). The pool key being
     *   present is intentional: it lets future reconnect attempts skip duplicate work and
     *   signals "we tried this one" to operators. Empty tool list means nothing routes to
     *   the downed server — exactly the spec's "excluded from routing" semantic.
     */
    @Test
    void mcpServerUnreachable_connectFailsSoftAndAgentRunStillCompletes() {
        HttpHeaders auth = authenticatedHeaders("mcp-unreachable-runner");

        String extId = "mcp-unreachable-" + UUID.randomUUID();
        String url = "http://localhost:1/sse";

        ExtensionRegistrationEntity entity = new ExtensionRegistrationEntity();
        entity.setId(extId);
        entity.setName("T040 unreachable probe");
        entity.setType("MCP");
        entity.setUrl(url);
        entity.setActive(true);
        entity.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        extensionRepository.save(entity);

        mcpConnectionPool.connect(extId);

        assertTrue(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "fail-soft contract: pool reserves the key on transport failure (McpConnectionPool.java:102)");
        assertTrue(mcpConnectionPool.getToolCallbacks(extId).isEmpty(),
                "failed connect leaves an empty tool list — nothing routes to the downed MCP server");

        String agentId = createAgent(auth, "T040 unreachable agent");
        fakeModel.respondWith("T040 case 3 reply");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "T040 case 3 prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, run.getStatusCode(),
                "broken MCP server registration must NOT surface as a 500 on agent runs — the pool isolates failure");
        assertEquals("COMPLETED", run.getBody().get("status"),
                "agent_runs.status must still land COMPLETED when an MCP entry is unreachable");
    }

    // ─── §17 Case 4 — tool invocation appears in ToolCallDTO + audit log (DISABLED) ───

    /**
     * Spec §17.4. To pin this contract end-to-end we'd need a real MCP server that
     *   completes the full SSE handshake (initialize, tools/list, tools/call) AND
     *   declares at least one callable tool that an agent can invoke. WireMock alone
     *   cannot script the bidirectional MCP-protocol message stream. Defer until a
     *   {@code TestMcpServer} support class lands (or until this is covered by a
     *   recorded-cassette contract test outside the integration tier).
     *
     *   Adjacent coverage: agent tool invocation auditing in general is exercised by
     *   the runs-suite tests; what is NOT covered today is the MCP-specific tool
     *   surface name landing in {@code ToolCallDTO}. The {@code @Disabled} keeps the
     *   spec-aligned case count at 6 and surfaces the gap in surefire output.
     */
    @Test
    @Disabled("T040(4): MCP tool invocation -> ToolCallDTO + audit log requires a real MCP server stub that completes the SSE handshake and declares tools. WireMock alone cannot script the bidirectional MCP-protocol message stream. Defer until a TestMcpServer support class lands.")
    void mcpToolInvocationDuringRun_recordedInToolCallDtoAndAuditLog() {
        // intentional placeholder
    }

    // ─── §17 Case 5 — removal closes connection cleanly (HTTP/SSE, not stdio) ───

    /**
     * Spec §17.5 phrases this as "closes the stdio process cleanly (no zombies)".
     *   Production transport is {@link io.modelcontextprotocol.client.transport.HttpClientSseClientTransport}
     *   — HTTP/SSE, not stdio. There is no child process to zombie. The contract that
     *   actually IS shipped: {@code McpConnectionPool.disconnect(id)} removes the entry
     *   from {@code activeConnections}, removes the {@code McpSyncClient} from
     *   {@code mcpClients}, and calls {@code closeGracefully()} on the client (line 113-114).
     *   We pin the pool-state half (id removed, tool list empty after the call) and
     *   idempotency (a second disconnect is a no-op, not a throw).
     *
     *   When/if a stdio transport variant is introduced, this case should be extended
     *   to assert "no orphaned process file descriptors" via the OS process API.
     */
    @Test
    void disconnectMcpExtension_removesPoolEntryAndIsIdempotent() {
        String extId = "mcp-remove-" + UUID.randomUUID();
        String url = "http://localhost:1/sse";

        ExtensionRegistrationEntity entity = new ExtensionRegistrationEntity();
        entity.setId(extId);
        entity.setName("T040 remove probe");
        entity.setType("MCP");
        entity.setUrl(url);
        entity.setActive(true);
        entity.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        extensionRepository.save(entity);

        mcpConnectionPool.connect(extId);
        assertTrue(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "precondition: extension must be in the pool before disconnect");

        mcpConnectionPool.disconnect(extId);

        assertFalse(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "disconnect must remove the extension id from activeConnections");
        assertTrue(mcpConnectionPool.getToolCallbacks(extId).isEmpty(),
                "post-disconnect, getToolCallbacks(id) must yield an empty list (Collections.emptyList fallback)");

        mcpConnectionPool.disconnect(extId);
        assertFalse(mcpConnectionPool.getConnectedExtensionIds().contains(extId),
                "double-disconnect must remain a no-op (line 119 'nothing to disconnect' branch)");
    }

    // ─── §17 Case 6 — circuit breaker on slow tool (DISABLED) ───

    /**
     * Spec §17.6. Production reality: {@link McpConnectionPool} has no Resilience4j
     *   wiring at all — no {@code @CircuitBreaker}, no {@code @Retry}, no
     *   {@code @TimeLimiter} around {@code connect()} or the per-tool callback path.
     *   The fail-soft empty-list-on-error behaviour (line 100-103) is the closest current
     *   contract and is already pinned by case 3. A genuine circuit-breaker assertion
     *   needs (1) a slow MCP server stub that the transport can actually handshake with,
     *   (2) Resilience4j wiring on the dispatch path, and (3) metrics/state inspection.
     *   Flip this test to assert circuit-open / half-open transitions when (2) lands.
     */
    @Test
    @Disabled("T040(6): MCP slow-tool circuit breaker is not implemented. McpConnectionPool has no Resilience4j @CircuitBreaker / @Retry / @TimeLimiter wrapping connect() or tool invocation. The fail-soft empty-list-on-error behaviour (line 100-103) is the closest current contract, already pinned by case 3.")
    void mcpTimeoutOnSlowTool_circuitBreakerTripsAndShortCircuitsSubsequentCalls() {
        // intentional placeholder
    }

    // ─── helpers ───

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T040 fixture");
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

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before run endpoints reference it");
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-mcp-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
