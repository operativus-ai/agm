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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pins the current behavior of {@code AgentAdminService.updateAgent}
 *   on an idempotent update — i.e., re-PUTting the EXACT same definition payload back to
 *   the server. The question is: does {@code logAudit(AuditActionType.UPDATE, …)} still
 *   write a row when no field actually changed?
 *
 *   <p>The code in {@code AgentAdminService.updateAgent} unconditionally calls
 *   {@code logAudit} at the end of the method regardless of whether any field changed.
 *   This test pins that fact: an idempotent UPDATE PRODUCES a new audit row (audit-noise
 *   tradeoff: completeness over volume).
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>PUT with identical body</b> writes one new UPDATE audit row each call. If
 *         this changes to "compare before write" semantics (skip when unchanged), the
 *         assertion flips and the audit-noise problem is solved.</li>
 *     <li><b>Two consecutive identical PUTs</b> produce 2 new audit rows (consistent
 *         per-call). Pinning this guards against a "first-call-wins, then-skip" cache.</li>
 *   </ul>
 *
 *   <p>Why this matters: if an automated client (e.g., GitOps sync) repeatedly re-applies
 *   the same agent config, today the audit table accumulates a row per sync iteration.
 *   That's both an audit-table growth problem AND a forensic-noise problem when reviewing
 *   "real" changes. The test makes the current trade-off visible.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentAuditIdempotentUpdateRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("audit-idempotent-update-admin",
                "audit-idempotent-update-admin@test.local", "pass-aiua-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
        // Seed a model row so the agent FK resolves.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools,
                                    supports_vision, supports_system_instructions, model_type,
                                    created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true,
                        'CHAT', NOW())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void identicalUpdatePutWritesNewUpdateAuditRow() {
        String agentId = "idempotent-update-" + UUID.randomUUID();
        seedAgent(agentId);

        long baselineUpdates = countAuditRows(agentId, "UPDATE");

        // First PUT with the canonical body.
        Map<String, Object> body = baseBody(agentId);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.PUT,
                new HttpEntity<>(body, adminAuth),
                JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "first PUT must succeed; got " + response.getStatusCode());

        long afterFirst = countAuditRows(agentId, "UPDATE");
        assertEquals(baselineUpdates + 1, afterFirst,
                "first PUT must write exactly one UPDATE audit row; baseline="
                        + baselineUpdates + " afterFirst=" + afterFirst);
    }

    @Test
    void twoConsecutiveIdenticalUpdatesProduceTwoUpdateAuditRows() {
        String agentId = "idempotent-twice-" + UUID.randomUUID();
        seedAgent(agentId);

        long baselineUpdates = countAuditRows(agentId, "UPDATE");

        Map<String, Object> body = baseBody(agentId);

        for (int i = 0; i < 2; i++) {
            ResponseEntity<Map<String, Object>> response = rest.exchange(
                    url("/api/admin/agents/" + agentId),
                    HttpMethod.PUT,
                    new HttpEntity<>(body, adminAuth),
                    JSON_MAP);
            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    "PUT #" + i + " must succeed; got " + response.getStatusCode());
        }

        long afterTwo = countAuditRows(agentId, "UPDATE");
        assertEquals(baselineUpdates + 2, afterTwo,
                "TWO identical PUTs must write TWO UPDATE rows (current behavior — pinning "
                        + "audit-completeness-over-volume tradeoff); baseline="
                        + baselineUpdates + " afterTwo=" + afterTwo
                        + ". If this changes to skip-when-unchanged, update this assertion.");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void seedAgent(String id) {
        jdbc.update("""
                INSERT INTO agents (id, name, description, instructions, model_id, active,
                                    security_tier, compliance_tier, maintenance_mode, version,
                                    org_id, created_at, updated_at)
                VALUES (?, 'idempotent-test-agent', 'desc', 'instructions', 'gpt-4o-mini',
                        true, 1, 'TIER_1_STANDARD', false, 0, ?, NOW(), NOW())
                """, id, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    /**
     * Returns the body shape the API expects for PUT /api/admin/agents/{id}. Uses the JSON
     * property names defined on AgentDefinition (`agentId` for `id`, `model` for `modelId`).
     */
    private Map<String, Object> baseBody(String agentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", "idempotent-test-agent");
        body.put("description", "desc");
        body.put("instructions", "instructions");
        body.put("model", "gpt-4o-mini");
        return body;
    }

    private long countAuditRows(String agentId, String action) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = ?",
                Long.class, agentId, action);
        assertNotNull(count, "audit row count query returned null");
        return count;
    }
}
