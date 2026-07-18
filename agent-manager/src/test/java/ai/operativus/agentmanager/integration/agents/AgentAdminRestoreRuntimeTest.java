package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code POST /api/admin/agents/{id}/restore} —
 *   {@link ai.operativus.agentmanager.control.controller.AgentAdminController#restoreAgent}.
 *   The soft-deleted-agent restore path was untested before this canary.
 *
 *   <p>Contract from
 *   {@link ai.operativus.agentmanager.control.service.AgentAdminService#restoreAgent}:
 *   <ul>
 *     <li>Tenant-scoped via {@code agentRepository.findByIdAndOrgId(id, callerOrgId())} —
 *         cross-tenant requests surface as {@code ResourceNotFoundException} → 404.</li>
 *     <li>Always sets {@code active=true}; no-op safe when already active.</li>
 *     <li>Logs an audit row with action {@code RESTORE}.</li>
 *     <li>Returns 204 No Content from the controller.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAdminRestoreRuntimeTest extends BaseIntegrationTest {

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
    void restoreSoftDeletedAgent_returns204_flipsActiveBackToTrue_andWritesAuditRow() {
        String orgId = "org-restore-happy-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("restore-happy", orgId);
        String agentId = seedAgent(orgId, /*active=*/ false);

        long auditRowsBefore = countRestoreAuditRows(agentId);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "successful restore must return 204 No Content; got " + resp.getStatusCode());

        Boolean isActive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
        assertEquals(Boolean.TRUE, isActive,
                "agents.active must flip to true after restore; got " + isActive);

        long auditRowsAfter = countRestoreAuditRows(agentId);
        assertTrue(auditRowsAfter > auditRowsBefore,
                "restore must write at least one RESTORE audit row; before=" + auditRowsBefore
                        + " after=" + auditRowsAfter);
    }

    @Test
    void restoreUnknownAgentId_returns404_andPersistsNoAuditRow() {
        String orgId = "org-restore-404-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("restore-404", orgId);
        String unknownId = UUID.randomUUID().toString();

        long auditRowsBefore = countAllRestoreAuditRows();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + unknownId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "restore on unknown id must return 404 (not 500) — "
                        + "ResourceNotFoundException maps via GlobalExceptionHandler");
        assertEquals(auditRowsBefore, countAllRestoreAuditRows(),
                "no audit row may be written for a failed restore");
    }

    @Test
    void restoreAlreadyActiveAgent_returns204_andStaysActive_noOpSafe() {
        String orgId = "org-restore-noop-" + UUID.randomUUID();
        HttpHeaders auth = registerLoginWithOrg("restore-noop", orgId);
        String agentId = seedAgent(orgId, /*active=*/ true);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                Void.class);

        assertAll("restore on already-active is no-op safe",
                () -> assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                        "must still return 204 — endpoint is idempotent"),
                () -> assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId),
                        "active must stay true"));
    }

    @Test
    void restoreCrossTenantAgent_returns404_andOwnerOrgRowUnmodified() {
        String orgA = "org-restore-cross-A-" + UUID.randomUUID();
        String orgB = "org-restore-cross-B-" + UUID.randomUUID();
        HttpHeaders authA = registerLoginWithOrg("restore-cross-a", orgA);
        registerLoginWithOrg("restore-cross-b", orgB);

        // Agent owned by org B, in soft-deleted state.
        String agentId = seedAgent(orgB, /*active=*/ false);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/admin/agents/" + agentId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(authA),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "cross-tenant restore must return 404 (existence-leak protection); got "
                        + resp.getStatusCode());

        Boolean stillInactive = jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
        assertEquals(Boolean.FALSE, stillInactive,
                "owner org's row must remain in its soft-deleted state — cross-tenant call "
                        + "must NOT mutate state");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String seedAgent(String orgId, boolean active) {
        String agentId = "agent-restore-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', ?, ?, now(), now())
                """, agentId, "Restore Test Agent", active, orgId);
        return agentId;
    }

    private long countRestoreAuditRows(String agentId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'RESTORE'",
                Long.class, agentId);
        return n != null ? n : 0L;
    }

    private long countAllRestoreAuditRows() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE action = 'RESTORE'",
                Long.class);
        return n != null ? n : 0L;
    }
}
