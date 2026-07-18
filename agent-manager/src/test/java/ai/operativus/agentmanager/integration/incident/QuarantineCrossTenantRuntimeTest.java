package ai.operativus.agentmanager.integration.incident;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the cross-tenant guard on
 *   {@link ai.operativus.agentmanager.control.service.IncidentResponseService#quarantineAgent}
 *   and {@link ai.operativus.agentmanager.control.service.IncidentResponseService#unquarantineAgent}.
 *
 *   <p><b>Why this matters:</b> quarantine flips {@code agents.maintenance_mode = true},
 *   cancels active runs, and disables agent credentials. Unquarantine reverses those steps.
 *   Both are destructive AND persistent (vs. the in-memory FinOps baseline). Pre-fix the
 *   service used {@code agentRepository.findById(agentId)} with NO tenant column check —
 *   any tenant's admin could quarantine any other tenant's agent. The class-level
 *   {@code @PreAuthorize("hasRole('ADMIN')")} gate on
 *   {@code IncidentResponseController.quarantine} stops a regular user but does NOT stop a
 *   foreign-tenant admin; admin-role ≠ admin-of-target-tenant.
 *
 *   <p>Same exploit shape as PR #998 (Memory.deleteMemories) and PR #1007 (FinOps baseline).
 *   Service-layer fix: read {@code AgentContextHolder.getOrgId()} and call
 *   {@code findByIdAndOrgId(agentId, callerOrgId)}; cross-tenant resolves to
 *   {@code Optional.empty} → {@code ResourceNotFoundException} → 404 via the global handler
 *   (existence-leak protection).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class QuarantineCrossTenantRuntimeTest extends BaseIntegrationTest {

    @Test
    void quarantineCrossTenantAgent404_andTargetRowUnchanged() {
        HttpHeaders adminA = registerLoginWithOrg("quar-cross-a-admin", "quar-org-A");
        HttpHeaders adminB = registerLoginWithOrg("quar-cross-b-admin", "quar-org-B");

        String bAgentId = "agent-quar-b-" + UUID.randomUUID();
        seedAgent(bAgentId, "quar-org-B", /* maintenanceMode = */ false);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/admin/agents/" + bAgentId + "/quarantine"),
                HttpMethod.POST,
                jsonEntity(Map.of("reason", "hostile cross-tenant attempt"), adminA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /admin/agents/{B-agent-id}/quarantine as A must return 404 "
                        + "(existence-leak protection); got " + response.getStatusCode());

        Boolean stillNotInMaintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, bAgentId);
        assertEquals(Boolean.FALSE, stillNotInMaintenance,
                "cross-tenant quarantine must NOT have flipped B's agent into maintenance mode; "
                        + "got maintenance_mode=" + stillNotInMaintenance);

        // Audit-row count must be unchanged — no AGENT_QUARANTINE row written by A's call.
        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_QUARANTINE'",
                Long.class, bAgentId);
        assertEquals(0L, auditCount == null ? 0L : auditCount,
                "no AGENT_QUARANTINE audit row may be written for a cross-tenant attempt");

        // Sanity: B's own admin can still quarantine its own agent (positive control —
        // the guard scopes deletion, not the entire quarantine surface).
        ResponseEntity<Map<String, Object>> bSelf = rest.exchange(
                url("/api/v1/admin/agents/" + bAgentId + "/quarantine"),
                HttpMethod.POST,
                jsonEntity(Map.of("reason", "B-initiated self-quarantine"), adminB),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, bSelf.getStatusCode(),
                "B's own admin must still be able to quarantine its own agent after A's blocked attempt");
        Boolean nowInMaintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, bAgentId);
        assertEquals(Boolean.TRUE, nowInMaintenance,
                "B's own quarantine call must succeed and flip maintenance_mode=true");
    }

    @Test
    void unquarantineCrossTenantAgent404_andTargetRemainsQuarantined() {
        HttpHeaders adminA = registerLoginWithOrg("unq-cross-a-admin", "unq-org-A");
        HttpHeaders adminB = registerLoginWithOrg("unq-cross-b-admin", "unq-org-B");

        String bAgentId = "agent-unq-b-" + UUID.randomUUID();
        // Seed B's agent already in maintenance — pre-quarantined by B (presumably for a
        // real compliance/security reason). A must NOT be able to reactivate it.
        seedAgent(bAgentId, "unq-org-B", /* maintenanceMode = */ true);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/admin/agents/" + bAgentId + "/unquarantine"),
                HttpMethod.POST,
                jsonEntity(Map.of("reason", "hostile cross-tenant reactivation"), adminA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "POST /admin/agents/{B-agent-id}/unquarantine as A must return 404; got "
                        + response.getStatusCode());

        Boolean stillInMaintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, bAgentId);
        assertEquals(Boolean.TRUE, stillInMaintenance,
                "cross-tenant unquarantine must NOT have flipped B's agent OUT of maintenance mode; "
                        + "got maintenance_mode=" + stillInMaintenance);
    }

    // ─── helpers ───

    private void seedAgent(String agentId, String orgId, boolean maintenanceMode) {
        jdbc.update("""
                INSERT INTO agents (id, name, active, security_tier, compliance_tier,
                                    maintenance_mode, version, org_id, created_at, updated_at)
                VALUES (?, ?, true, 1, 'TIER_1_STANDARD', ?, 0, ?, NOW(), NOW())
                """, agentId, agentId, maintenanceMode, orgId);
    }

    private static HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body, HttpHeaders auth) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(auth);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }
}
