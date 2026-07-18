package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.core.model.TenantConstants;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins error-path edge cases on
 *   {@code POST /api/admin/agents/{id}/rollback/{auditId}} —
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#rollbackAgent}.
 *
 *   <p>The endpoint has multiple branches that should each surface a deterministic
 *   error code. This test pins those branches; the happy path is partially exercised
 *   by the in-place {@code updateAgent} path and the {@code logAudit} side-effect
 *   covered by other agent-audit runtime tests.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Invalid {@code auditId}</b> → 404 (ResourceNotFoundException →
 *         GlobalExceptionHandler).</li>
 *     <li><b>{@code auditId} belongs to a different agent</b> → 400 (BusinessValidationException
 *         "Audit record does not belong to agent: …"). This is the security-relevant case —
 *         without this guard, an attacker could rollback agent B's config using an audit
 *         row from agent A that they happen to know the ID of.</li>
 *   </ul>
 *
 *   <p>Note: the rollback endpoint lives on {@code AgentAdminController}, which is class-level
 *   {@code @PreAuthorize("hasRole('ADMIN')")} (gated by #969). The caller therefore needs
 *   {@code ROLE_ADMIN} to pass the authz layer; these cases assert the business-error branches
 *   ({@code 404}/{@code 400}) reached PAST the gate, not the gate itself (that's
 *   {@code AgentAdminAuthzRuntimeTest}).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentRollbackRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders userAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        userAuth = authenticateAs("agent-rollback-user",
                "agent-rollback-user@test.local", "pass-aru-1234",
                // ROLE_ADMIN required to reach the admin rollback endpoint (/api/admin/agents,
                // gated since #969); these cases assert business errors (404/400), not authz.
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void rollbackWithNonExistentAuditIdReturns404() {
        String agentId = "rollback-agent-" + UUID.randomUUID();
        seedAgent(agentId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents/" + agentId + "/rollback/non-existent-audit-id"),
                HttpMethod.POST,
                new HttpEntity<>(null, userAuth),
                JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "rollback with non-existent auditId must return 404 "
                        + "(ResourceNotFoundException); got " + response.getStatusCode());
    }

    @Test
    void rollbackWithAuditIdBelongingToAnotherAgentReturns400() {
        String agentA = "rollback-agent-a-" + UUID.randomUUID();
        String agentB = "rollback-agent-b-" + UUID.randomUUID();
        seedAgent(agentA);
        seedAgent(agentB);

        // Seed an audit row that belongs to agentA.
        String auditOnA = "audit-on-a-" + UUID.randomUUID();
        seedAudit(auditOnA, agentA,
                "{\"agentId\":\"" + agentA + "\",\"name\":\"x\",\"description\":\"d\","
                        + "\"instructions\":\"i\",\"model\":\"m\"}");

        // Attempt to rollback agentB using agentA's audit row.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents/" + agentB + "/rollback/" + auditOnA),
                HttpMethod.POST,
                new HttpEntity<>(null, userAuth),
                JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "rollback with auditId from a different agent must return 400 "
                        + "(BusinessValidationException); got " + response.getStatusCode()
                        + ". Without this guard, an attacker could rollback agent B's config "
                        + "using an audit row from agent A.");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, ?, ?, 'test-instructions', NULL, true, 1, 'TIER_1_STANDARD',
                        false, 0, ?, NOW(), NOW())
                """, id, id, "desc-" + id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private void seedAudit(String auditId, String agentId, String changeset) {
        jdbc.update("""
                INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset,
                                          version_number, created_at)
                VALUES (?, ?, ?, 'UPDATE', 'fixture', ?::jsonb, 1, NOW())
                """, auditId, agentId, TenantConstants.DEFAULT_SYSTEM_ORG, changeset);
    }
}
