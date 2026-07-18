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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/export} and
 *   {@code POST /api/admin/agents/import} —
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#exportAgent}
 *   (just delegates to {@code getAgent}) and
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#importAgent}
 *   (creates a new entity, stamps caller's orgId, logs IMPORT audit).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentExportImportRuntimeTest extends BaseIntegrationTest {

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
    void exportExistingAgent_returnsAgentDefinitionShape() {
        String orgId = "org-export-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("export-happy", orgId);
        String agentId = seedAgent(orgId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/export"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(agentId, resp.getBody().get("agentId"),
                "AgentDefinition.id serializes as 'agentId'");
    }

    @Test
    void exportUnknownAgentId_returns404() {
        String orgId = "org-export-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("export-404", orgId);
        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/export"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void importWithoutId_returns400() {
        String orgId = "org-import-null-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("import-null", orgId);

        Map<String, Object> body = new HashMap<>();
        // Intentionally no agentId / id.
        body.put("name", "no-id-agent");
        body.put("description", "x");
        body.put("instructions", "x");
        body.put("model", "gpt-4o-mini");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/import"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "import without id must 400 (BusinessValidationException); got "
                        + resp.getStatusCode());
    }

    @Test
    void importStampsCallerOrgId_ignoringBodyOrgId() {
        String callerOrg = "org-import-caller-" + UUID.randomUUID();
        String forgedOrg = "org-import-forged-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("import-caller", callerOrg);

        String newAgentId = "agent-import-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", newAgentId);
        body.put("name", "Imported agent");
        body.put("description", "test");
        body.put("instructions", "be helpful");
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
        body.put("orgId", forgedOrg);  // body's orgId must NOT win

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents/import"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());

        // Verify persisted org_id is the caller's, not the body's.
        String persistedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, newAgentId);
        assertAll("import stamps caller orgId",
                () -> assertEquals(callerOrg, persistedOrgId,
                        "persisted org_id must be caller's, not body's; got " + persistedOrgId),
                () -> assertNotNull(jdbc.queryForObject(
                        "SELECT id FROM agents WHERE id = ?", String.class, newAgentId),
                        "agent row must be created"));
    }

    private String seedAgent(String orgId) {
        String agentId = "agent-exp-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Export test agent", orgId);
        return agentId;
    }
}
