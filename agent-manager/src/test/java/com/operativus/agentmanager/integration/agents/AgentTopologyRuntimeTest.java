package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/topology} —
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#getAgentTopology}.
 *   Returns a {@code TopologyDTO} with nodes (the agent + tools + team members) and
 *   edges between them. Tenant-scoped.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentTopologyRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void topologyForSingleAgent_returnsOnlyRootNode_noEdges() {
        String orgId = "org-topo-single-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("topo-single", orgId);
        String agentId = seedAgent(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/topology"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) body.get("edges");
        assertAll("single-agent topology contract",
                () -> assertNotNull(nodes, "nodes array must be present"),
                () -> assertEquals(1, nodes.size(),
                        "single agent with no tools / not a team must yield exactly 1 node "
                                + "(itself); got " + nodes.size()),
                () -> assertEquals(agentId, nodes.get(0).get("id"),
                        "the single node must be the agent itself"),
                () -> assertEquals("agent", nodes.get(0).get("type"),
                        "root node type is 'agent'"),
                () -> assertTrue(edges == null || edges.isEmpty(),
                        "single-agent topology must have no edges; got " + edges));
    }

    @Test
    void topologyForUnknownAgentId_returns404() {
        String orgId = "org-topo-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("topo-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/topology"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void topologyForCrossTenantAgent_returns404_noStructureLeak() {
        String orgA = "org-topo-A-" + UUID.randomUUID();
        String orgB = "org-topo-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("topo-a", orgA);
        registerLoginWithOrg("topo-b", orgB);
        String foreignAgent = seedAgent(orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/topology"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant topology must 404 — must not leak agent structure");
    }

    private String seedAgent(String orgId) {
        String agentId = "agent-topo-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Topology test agent", orgId);
        return agentId;
    }
}
