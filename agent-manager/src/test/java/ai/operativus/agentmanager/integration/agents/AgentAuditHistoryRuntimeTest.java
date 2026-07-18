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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code GET /api/admin/agents/{id}/audit} — paginated
 *   agent_audits history for a specific agent. Tenant-scoped; ordered by created_at DESC.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAuditHistoryRuntimeTest extends BaseIntegrationTest {

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
    void auditHistoryForAgentWithEntries_returnsPagedResults() {
        String orgId = "org-audit-paged-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("audit-paged", orgId);
        String agentId = seedAgent(orgId);
        seedAudit(agentId, orgId, "CREATE", LocalDateTime.now().minusHours(2));
        seedAudit(agentId, orgId, "UPDATE", LocalDateTime.now().minusHours(1));
        seedAudit(agentId, orgId, "RESTORE", LocalDateTime.now());

        Map<String, Object> body = get(auth, "/api/admin/agents/" + agentId + "/audit");

        assertAll("audit history paged contract",
                () -> assertEquals(3L, totalElements(body)),
                () -> assertEquals(3, contentSize(body)));
    }

    @Test
    void auditHistoryForAgentWithNoEntries_returns200WithZeroTotal() {
        String orgId = "org-audit-empty-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("audit-empty", orgId);
        String agentId = seedAgent(orgId);

        Map<String, Object> body = get(auth, "/api/admin/agents/" + agentId + "/audit");

        assertEquals(0L, totalElements(body),
                "agent with no audit rows must return totalElements=0 (not 404, not null)");
    }

    @Test
    void auditHistoryForUnknownAgentId_returns404() {
        String orgId = "org-audit-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("audit-404", orgId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + UUID.randomUUID() + "/audit"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void auditHistoryForCrossTenantAgent_returns404_noAuditLeak() {
        String orgA = "org-audit-A-" + UUID.randomUUID();
        String orgB = "org-audit-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("audit-a", orgA);
        registerLoginWithOrg("audit-b", orgB);
        String foreignAgent = seedAgent(orgB);
        seedAudit(foreignAgent, orgB, "CREATE", LocalDateTime.now());

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + foreignAgent + "/audit"),
                HttpMethod.GET,
                new HttpEntity<>(authA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant audit must 404 — must not leak audit rows from other orgs");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String seedAgent(String orgId) {
        String agentId = "agent-audit-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Audit test agent", orgId);
        return agentId;
    }

    private void seedAudit(String agentId, String orgId, String action, LocalDateTime createdAt) {
        // agent_audits is append-only (trg_agent_audits_immutable trigger). Use the
        // bypass flag so this seed insert doesn't fail. Mirrors the pattern in
        // DataRetentionService / ComplianceExportService.
        jdbc.execute("SET LOCAL agm.audit_immutability_bypass = 'true'");
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, created_at)
                VALUES (?, ?, ?, ?, 'audit-test-user', ?)
                """, UUID.randomUUID().toString(), agentId, orgId, action, createdAt);
    }

    private Map<String, Object> get(HttpHeaders auth, String path) {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url(path), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "happy path must 200; got " + resp.getStatusCode());
        assertNotNull(resp.getBody());
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private static long totalElements(Map<String, Object> body) {
        Object page = body.get("page");
        if (page instanceof Map<?, ?> pageMap) {
            Object n = pageMap.get("totalElements");
            if (n instanceof Number num) return num.longValue();
        }
        Object n = body.get("totalElements");
        if (n instanceof Number num) return num.longValue();
        throw new AssertionError("totalElements not found; body=" + body);
    }

    @SuppressWarnings("unchecked")
    private static int contentSize(Map<String, Object> body) {
        List<Object> content = (List<Object>) body.get("content");
        return content == null ? 0 : content.size();
    }
}
