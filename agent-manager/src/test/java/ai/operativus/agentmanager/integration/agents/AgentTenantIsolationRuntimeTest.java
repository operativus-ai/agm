package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the agents surface
 *   (AgentAdminService + DatabaseAgentRegistry). ADMIN of org A cannot list, fetch,
 *   update, delete, restore, or clone agents belonging to org B. Cross-tenant lookups
 *   return 404 / empty list as appropriate (existence-leak protection). POST stamps
 *   the caller's orgId regardless of any orgId field in the request body.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    @Test
    void listReturnsOnlyCallerOrgAgents() {
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-list", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-list", "agent-iso-org-B");

        createAgent(orgA, "Agent Alpha");
        createAgent(orgA, "Agent Beta");
        createAgent(orgB, "Agent Gamma");

        ResponseEntity<Map<String, Object>> aPage = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, aPage.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aPage.getBody().get("content");
        assertEquals(2, aContent.size(),
                "org A listing must contain exactly A's 2 agents; got " + aContent.size());
        assertTrue(aContent.stream().allMatch(a -> String.valueOf(a.get("name")).startsWith("Agent A")
                        || String.valueOf(a.get("name")).startsWith("Agent B")),
                "every row in A's listing must be an A-org agent; got " + aContent);

        ResponseEntity<Map<String, Object>> bPage = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bPage.getBody().get("content");
        assertEquals(1, bContent.size(),
                "org B listing must contain exactly B's 1 agent; got " + bContent.size());
    }

    @Test
    void put404ForCrossTenantAgentAndRowUnmodified() {
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-put", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-put", "agent-iso-org-B");

        String bId = createAgent(orgB, "B's Patch-Probe");

        Map<String, Object> body = new HashMap<>();
        body.put("agentId", bId);
        body.put("name", "should-never-apply");
        body.put("description", "cross-tenant injection attempt");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bId),
                HttpMethod.PUT,
                new HttpEntity<>(body, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PUT cross-tenant must return 404; got " + response.getStatusCode());

        String storedName = jdbc.queryForObject(
                "SELECT name FROM agents WHERE id = ?",
                String.class, bId);
        assertEquals("B's Patch-Probe", storedName,
                "cross-tenant PUT must not have modified B's row; got name=" + storedName);
    }

    @Test
    void deleteCrossTenantAgentIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-del", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-del", "agent-iso-org-B");

        String bId = createAgent(orgB, "B's Delete-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                String.class);
        // Cross-tenant DELETE surfaces as 404 (existence-leak protection — same
        // pattern as the knowledge/schedules/workflows/teams tenant-isolation surfaces).
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "cross-tenant DELETE must return 404; got " + response.getStatusCode());

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE id = ? AND active = true",
                Long.class, bId);
        assertEquals(1L, count == null ? 0L : count,
                "cross-tenant DELETE must not have soft-deleted B's agent");
    }

    @Test
    void getById404ForCrossTenantAgent() {
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-get", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-get", "agent-iso-org-B");

        String bId = createAgent(orgB, "B's Get-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/admin/agents/{B-id} as A must return 404 (existence-leak protection); got "
                        + response.getStatusCode());
    }

    @Test
    void exportCrossTenantAgent404_andNoBodyLeaked() {
        // Export is the highest data-exfil risk on this surface — it returns the full
        // AgentDefinition including instructions, tools, knowledge bindings, and
        // model_id. AgentAdminService.exportAgent uses findByIdAndOrgId so cross-tenant
        // calls 404 before the entity is even loaded.
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-export", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-export", "agent-iso-org-B");

        String bId = createAgent(orgB, "B's Export-Probe");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bId + "/export"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/admin/agents/{B-id}/export as A must return 404; got "
                        + response.getStatusCode());
        String body = response.getBody();
        assertTrue(body == null || !body.contains("B's Export-Probe"),
                "404 response body must NOT leak B's agent name; got " + body);
    }

    @Test
    void restoreCrossTenantAgent404_andTargetRowRemainsInactive() {
        // restoreAgent flips active=false → true. If the cross-tenant guard were
        // missing, Org A could resurrect Org B's deliberately-deleted agent (e.g.
        // one disabled for a compliance reason), pulling it back into production
        // traffic in B's tenant without B's knowledge.
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-restore", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-restore", "agent-iso-org-B");

        String bId = createAgent(orgB, "B's Restore-Probe");

        // B soft-deletes its own agent so there's something for A to attempt to revive.
        ResponseEntity<Void> bDelete = rest.exchange(
                url("/api/admin/agents/" + bId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgB),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, bDelete.getStatusCode(),
                "fixture: B's self-delete must succeed before A's restore probe");
        Boolean preActive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, bId);
        assertEquals(Boolean.FALSE, preActive,
                "fixture invariant: B's agent must be inactive before A's restore attempt");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /api/admin/agents/{B-id}/restore as A must return 404; got "
                        + response.getStatusCode());

        Boolean postActive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, bId);
        assertEquals(Boolean.FALSE, postActive,
                "cross-tenant restore must NOT have flipped B's agent back to active; got active="
                        + postActive);
    }

    @Test
    void rollbackCrossTenantAgent404_andTargetRowUnchanged() {
        // rollback restores an agent's configuration from a stored audit snapshot.
        // AgentAdminService.rollbackAgent loads the audit record first (line ~440),
        // then walks audit.agentId → agents.org_id via findByIdAndOrgId to enforce
        // that the caller owns the target agent. Cross-tenant: throws
        // ResourceNotFoundException → 404. Without the guard, Org A could rewrite
        // Org B's agent configuration from any audit snapshot.
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-rollback", "agent-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("agent-iso-b-rollback", "agent-iso-org-B");

        String bAgentId = createAgent(orgB, "B's Rollback-Probe");
        // Stamp the agent row's name to a known pre-rollback value so we can detect
        // whether a successful rollback (cross-tenant exploit) clobbered it.
        jdbc.update("UPDATE agents SET name = ? WHERE id = ?",
                "B's Rollback-Probe PRE-ROLLBACK", bAgentId);

        // Seed an audit row for B's agent with a different name in the changeset —
        // a successful rollback would overwrite agents.name with this value.
        String auditId = UUID.randomUUID().toString();
        String changeset = "{\"id\":\"" + bAgentId + "\",\"name\":\"REWRITTEN_BY_A\",\"description\":\"hijack\","
                + "\"instructions\":\"Be malicious\",\"modelId\":\"gpt-4o-mini\",\"isReasoningEnabled\":false,"
                + "\"isTeam\":false,\"requiresPiiRedaction\":false,\"approvedForProduction\":false,"
                + "\"maintenanceMode\":false,\"active\":true,\"enforceJsonOutput\":false}";
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset, version_number, created_at)
                VALUES (?, ?, ?, 'UPDATE', 'fixture-user', ?::jsonb, 1, NOW())
                """, auditId, bAgentId, "agent-iso-org-B", changeset);

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + bAgentId + "/rollback/" + auditId),
                HttpMethod.POST,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /api/admin/agents/{B-id}/rollback/{auditId} as A must return 404; got "
                        + response.getStatusCode());

        String postName = jdbc.queryForObject(
                "SELECT name FROM agents WHERE id = ?", String.class, bAgentId);
        assertEquals("B's Rollback-Probe PRE-ROLLBACK", postName,
                "cross-tenant rollback must NOT have rewritten B's agent configuration; got name="
                        + postName);
    }

    @Test
    void postStampsCallerOrgIdIgnoringBodyOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("agent-iso-a-post", "agent-iso-org-A");

        String agentId = "agent-iso-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", "post-org-injection-attempt");
        body.put("description", "body claims org B");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini");
        body.put("orgId", "agent-iso-org-B");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);

        String createdId = agentId;
        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?",
                String.class, createdId);
        assertEquals("agent-iso-org-A", storedOrgId,
                "POST must stamp caller's orgId; body-injected orgId must be ignored. got=" + storedOrgId);
    }


    // ─── helpers ───

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-iso-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Created from AgentTenantIsolationRuntimeTest");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createAgent fixture must succeed; got " + resp.getStatusCode());
        return agentId;
    }
}
