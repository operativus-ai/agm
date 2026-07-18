package com.operativus.agentmanager.integration.mcp;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins tenant scoping on {@code POST /mcp/messages} with method
 *   {@code tools/list}. The dynamic tool list is built from
 *   {@code agentRegistry.findAll(false, AgentContextHolder.getOrgId())} — a regression
 *   that drops the orgId binding (or that adds a {@code null}-bypass fallback) would
 *   expose every tenant's agents as {@code run_<agentId>} entries to any authenticated
 *   caller. That's a remote agent-enumeration vector for MCP clients.
 *
 *   <p>Pin: seed agents in two orgs and verify an org-A authenticated MCP caller sees
 *   only their own agentId in the tool list. The org-B agentId must NOT appear as a
 *   {@code run_*} tool entry.
 *
 *   <p>Sibling pin to {@code RunsControllerCrossTenantIdorRuntimeTest} for the runs
 *   listing — same family of cross-tenant enumeration, different protocol surface.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class McpInboundCrossTenantToolListRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void mcpToolsList_orgAUserSeesOnlyOwnAgentInDynamicToolList() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgA = "org-mcp-tl-A-" + tag;
        String orgB = "org-mcp-tl-B-" + tag;

        HttpHeaders userA = registerLoginWithOrg("mcp-tl-userA-" + tag, orgA);
        userA.setContentType(MediaType.APPLICATION_JSON);

        // Seed FK preconditions + one agent per org.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        String agentA = "mcp-agent-A-" + tag;
        String agentB = "mcp-agent-B-" + tag;
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, 'agent A', 'gpt-4o-mini', true, ?, now(), now()),
                       (?, 'agent B', 'gpt-4o-mini', true, ?, now(), now())
                """, agentA, orgA, agentB, orgB);

        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/list",
                "params", Map.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/mcp/messages"), HttpMethod.POST,
                new HttpEntity<>(body, userA), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "tools/list as a tenant user must return 200 with the JSON-RPC envelope");

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) respBody.get("result");
        assertNotNull(result, "tools/list response must carry a result object");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertNotNull(tools, "tools/list result must include a tools array");

        String expectedToolName = "run_" + agentA;
        String forbiddenToolName = "run_" + agentB;

        assertTrue(tools.stream().anyMatch(t -> expectedToolName.equals(t.get("name"))),
                "org-A caller must see their own agent as " + expectedToolName + " in the dynamic "
                        + "tool list. Tools observed: " + tools);
        assertTrue(tools.stream().noneMatch(t -> forbiddenToolName.equals(t.get("name"))),
                "org-A caller must NOT see org-B's agent (" + forbiddenToolName + ") in the dynamic "
                        + "tool list. Presence here means tools/list leaked cross-tenant agent IDs "
                        + "to remote MCP callers — a fully external agent-enumeration vector. "
                        + "Tools observed: " + tools);
    }
}
