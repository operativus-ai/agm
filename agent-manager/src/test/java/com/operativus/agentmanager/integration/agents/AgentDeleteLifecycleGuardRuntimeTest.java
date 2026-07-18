package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the lifecycle-guard branches of
 *   {@link com.operativus.agentmanager.control.service.AgentAdminService#deleteAgent}.
 *   Delete is a SOFT delete (sets {@code active=false} — row preserved). Before the
 *   soft-delete fires, the service rejects with a {@code BusinessValidationException}
 *   if any run is in a non-terminal state ({@code RUNNING} or {@code PAUSED}).
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Delete blocked by RUNNING run</b> → 400. Prevents wiping out an agent whose
 *         runtime state still needs to converge.</li>
 *     <li><b>Delete blocked by PAUSED run</b> → 400. PAUSED runs are blocked-but-resumable;
 *         deleting their agent would orphan the resume path.</li>
 *     <li><b>Delete proceeds when all runs are TERMINAL (COMPLETED/FAILED/CANCELLED)</b>
 *         → 204 + {@code active=false}. The agent row is preserved (soft delete),
 *         so workflow/schedule/team FKs remain intact — no cascade needed.</li>
 *     <li><b>Soft-delete preserves the row</b> — post-delete the agent is queryable and
 *         the {@code active} flag is {@code false}. Pins the "row preserved" invariant
 *         that obviates cascade logic for workflow/schedule/team references.</li>
 *   </ul>
 *
 *   <p>Sibling to {@code AgentsCrudRuntimeTest} (which pins the same-org delete happy path
 *   + cross-tenant 403) and {@code AgentTenantIsolationRuntimeTest}. Neither sibling pins
 *   the lifecycle-guard branches that this test covers.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentDeleteLifecycleGuardRuntimeTest extends BaseIntegrationTest {

    private HttpHeaders adminAuth;

    @BeforeEach
    void resetState() {
        truncateDatabase();
        adminAuth = authenticateAs("agent-delete-lifecycle-admin",
                "agent-delete-lifecycle-admin@test.local", "pass-adla-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    @Test
    void deleteAgentWithRunningRunReturns400() {
        String agentId = "lifecycle-running-" + UUID.randomUUID();
        seedAgent(agentId);
        seedRun(agentId, "RUNNING");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "delete-with-RUNNING-run must return 400 (BusinessValidationException); "
                        + "got " + response.getStatusCode());

        // Agent row remains, active still true.
        assertEquals(Boolean.TRUE, activeFlag(agentId),
                "agent must NOT be marked inactive when delete is rejected by the guard");
    }

    @Test
    void deleteAgentWithPausedRunReturns400() {
        String agentId = "lifecycle-paused-" + UUID.randomUUID();
        seedAgent(agentId);
        seedRun(agentId, "PAUSED");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "delete-with-PAUSED-run must return 400; got " + response.getStatusCode());

        assertEquals(Boolean.TRUE, activeFlag(agentId),
                "agent must remain active=true when delete is rejected by the guard");
    }

    @Test
    void deleteAgentWithOnlyCompletedRunsSucceedsAndSoftDeletes() {
        String agentId = "lifecycle-terminal-" + UUID.randomUUID();
        seedAgent(agentId);
        seedRun(agentId, "COMPLETED");
        seedRun(agentId, "FAILED");
        seedRun(agentId, "CANCELLED");

        ResponseEntity<Void> response = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "delete-with-only-terminal-runs must return 204; got "
                        + response.getStatusCode());

        // Row preserved (soft delete), active flipped to false.
        Boolean active = activeFlag(agentId);
        assertEquals(Boolean.FALSE, active,
                "soft-delete must set active=false (row preserved); got " + active);

        // Explicit row-still-exists assertion — pins the "no cascade" invariant.
        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE id = ?", Long.class, agentId);
        assertEquals(1L, rowCount,
                "soft-delete must preserve the agent row (no cascade); got " + rowCount);
    }

    @Test
    void deleteAgentWithMixedTerminalAndRunningRunsIsBlockedByTheRunningOne() {
        String agentId = "lifecycle-mixed-" + UUID.randomUUID();
        seedAgent(agentId);
        seedRun(agentId, "COMPLETED");
        seedRun(agentId, "RUNNING");
        seedRun(agentId, "FAILED");

        ResponseEntity<String> response = rest.exchange(
                url("/api/admin/agents/" + agentId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminAuth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "delete is blocked if ANY run is non-terminal, regardless of how many "
                        + "terminal runs exist; got " + response.getStatusCode());
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

    private void seedRun(String agentId, String status) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, status, input, org_id,
                                         created_at, updated_at)
                VALUES (?, ?, NULL, ?, 'lifecycle-guard-fixture', ?, NOW(), NOW())
                """, "run-" + UUID.randomUUID(), agentId, status,
                TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private Boolean activeFlag(String agentId) {
        return jdbc.queryForObject(
                "SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
    }
}
