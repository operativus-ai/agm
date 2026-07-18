package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/versions} —
 *   currently a stub that returns the single current AgentDefinition wrapped in a
 *   list. Regression-locks the stub shape so any future implementation (e.g. reading
 *   from agent_audits to build a true version history) MUST update this assertion
 *   intentionally rather than silently expanding the response.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentVersionsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
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
    void versionsForExistingAgent_returnsList_containingCurrentDefinition_currentStubContract() {
        String orgId = "org-versions-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("versions-happy", orgId);
        String agentId = seedAgent(orgId);

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/versions"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size(),
                "Current stub returns exactly 1 element (the current AgentDefinition). "
                        + "When this becomes a real audit-backed history, update the assertion. "
                        + "Got " + resp.getBody().size());
        // AgentDefinition.id is serialized as "agentId" via @JsonProperty.
        assertEquals(agentId, resp.getBody().get(0).get("agentId"),
                "the returned element must be the queried agent");
    }

    @Test
    void versionsForUnknownAgentId_returns404() {
        String orgId = "org-versions-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("versions-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/versions"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void versionsForCrossTenantAgent_returns404_noLeak() {
        String orgA = "org-versions-A-" + UUID.randomUUID();
        String orgB = "org-versions-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("versions-a", orgA);
        registerLoginWithOrg("versions-b", orgB);
        String foreignAgent = seedAgent(orgB);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/versions"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant versions must 404 — no definition leak via response body");
    }

    private String seedAgent(String orgId) {
        String agentId = "agent-versions-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Versions test agent", orgId);
        return agentId;
    }
}
