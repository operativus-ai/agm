package com.operativus.agentmanager.integration.mcp;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins tenant scoping on {@code POST /mcp/messages} method
 *   {@code tools/call} with a {@code run_<agentId>} tool name. While
 *   {@code tools/list} hides another tenant's agents from enumeration
 *   (pinned by {@code McpInboundCrossTenantToolListRuntimeTest}), a guessed
 *   {@code run_<otherOrgsAgentId>} call still hits the dispatch path. The MCP
 *   handler forwards to {@code AgentControlService.run_agent} →
 *   {@code AgentService.run}; the tenant guard lives in
 *   {@code agentRegistry.findById(agentId, orgId)} which throws
 *   {@code ResourceNotFoundException} for cross-tenant agentIds.
 *
 *   <p>Without this pin a regression that resolves the agent without the orgId
 *   filter (or that catches the not-found and continues) would let any
 *   authenticated MCP caller invoke any tenant's agent by guessing its agentId —
 *   a remote agent-execution IDOR.
 *
 *   <p>Pin: org-A user POSTs {@code tools/call} with {@code name=run_<orgBAgentId>}
 *   and asserts the response is a JSON-RPC error envelope (no result, no agent
 *   execution). FakeChatModel must NOT receive any prompt — proves the dispatch
 *   short-circuited before reaching the agent.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class McpInboundCrossTenantToolCallRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        fakeModel.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void mcpToolsCall_orgAUserInvokingOrgBAgent_returnsJsonRpcError_andFakeChatModelNeverFires() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-mcp-tc-A-" + tag;
        String orgB = "org-mcp-tc-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("mcp-tc-userA-" + tag, orgA);
        userA.setContentType(MediaType.APPLICATION_JSON);

        // Seed an agent in org-B only — org-A has none.
        String orgBAgentId = "mcp-tc-agent-B-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'victim agent', 'gpt-4o-mini', true, ?, now(), now())
                """, orgBAgentId, orgB);

        // Script a response just in case the call leaks through — its presence in
        // receivedPrompts would prove the bypass.
        fakeModel.respondWith("THIS SHOULD NEVER BE OBSERVED — cross-tenant tool_call leaked.");

        String reqId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", reqId,
                "method", "tools/call",
                "params", Map.of(
                        "name", "run_" + orgBAgentId,
                        "arguments", Map.of("message", "leak test")));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, userA), JSON_MAP);
        // JSON-RPC errors ride inside the response envelope; HTTP stays 200.
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "JSON-RPC errors travel inside the response envelope, not as HTTP 4xx — "
                        + "the transport is still 200 per spec");

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        assertEquals(reqId, respBody.get("id"), "response id must echo the request id");

        // Pin: must be an error envelope. Either -32601 (Tool not found — agent not in caller's org)
        // or -32603 (Internal error — ResourceNotFoundException leaked through). Both are
        // acceptable indicators that the agent did NOT execute; the dispatch was blocked.
        Object error = respBody.get("error");
        Object result = respBody.get("result");
        assertNotNull(error,
                "cross-tenant tools/call must return a JSON-RPC error envelope. "
                        + "A null error means the agent ran for another tenant's caller — IDOR. "
                        + "Full response: " + respBody);
        assertTrue(result == null,
                "cross-tenant tools/call must NOT carry a result. Presence here means the "
                        + "agent executed cross-tenant — full response: " + respBody);

        // The strongest possible assertion: FakeChatModel never observed a prompt.
        // If the dispatch leaked to AgentService.run, the chat model would receive at
        // least one prompt before failing.
        assertTrue(fakeModel.receivedPrompts().isEmpty(),
                "FakeChatModel must NEVER receive a prompt on a cross-tenant tools/call — "
                        + "observing one means the org filter on agentRegistry.findById regressed "
                        + "and the dispatch actually invoked the LLM for the victim's agent. "
                        + "Received: " + fakeModel.receivedPrompts());
    }
}
