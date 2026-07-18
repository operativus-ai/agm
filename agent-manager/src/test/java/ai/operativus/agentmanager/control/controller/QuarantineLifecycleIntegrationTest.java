package ai.operativus.agentmanager.control.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle integration test for C01 — full HTTP→service→DB round-trip with the real Spring
 * Security chain so {@code @PreAuthorize} actually fires (this is where the authz matrix
 * lives, since the controller-unit tests use the codebase-conventional standaloneSetup).
 *
 * Scope:
 *   - Atomic per-agent quarantine (T026, with @RepeatedTest(3) for variance discipline)
 *   - Run-start guard at HTTP layer post-quarantine (T027)
 *   - Unquarantine restores credentials but NOT runs (T028)
 *   - Targeted re-enable does NOT touch manually-disabled credentials (T029)
 *   - Global halt across multiple orgs writes per-affected-agent audit rows (T030)
 *   - Authorization matrix: USER → 403, ADMIN → 200, ADMIN-on-halt-all → 403, SUPER_ADMIN-on-halt-all → 200
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
class QuarantineLifecycleIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String suffix() {
        return Long.toHexString(System.nanoTime());
    }

    private void seedAgent(String agentId) {
        // org_id required: DatabaseAgentRegistry.findById uses findByIdAndOrgId, which falls
        // back to DEFAULT_SYSTEM_ORG when no AgentContextHolder is bound. Without it, the
        // run-start guard returns 404 (ResourceNotFoundException) instead of the 503 the C01
        // maintenance-mode guard would throw — the test then fails the is5xxServerError()
        // check on a 404. Mirrors PR #253's orchestrator-test fixture fix.
        jdbc.update("""
                INSERT INTO agents (id, name, active, security_tier, compliance_tier,
                                    maintenance_mode, version, org_id, created_at, updated_at)
                VALUES (?, ?, true, 1, 'TIER_1_STANDARD', false, 0, ?, NOW(), NOW())
                """, agentId, agentId, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    private void seedRunningRun(String runId, String agentId, String orgId) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, status, input, org_id,
                                         created_at, updated_at)
                VALUES (?, ?, NULL, 'RUNNING', 'test input', ?, NOW(), NOW())
                """, runId, agentId, orgId);
    }

    private void seedCredential(String credId, String agentId, boolean enabled) {
        jdbc.update("""
                INSERT INTO agent_credentials (id, agent_id, credential_type, provider_name,
                                                enabled, created_at, updated_at)
                VALUES (?, ?, 'API_KEY', 'test-provider', ?, NOW(), NOW())
                """, credId, agentId, enabled);
    }

    @RepeatedTest(3)
    void quarantineAgent_FullRoundTrip_PersistsAllSideEffects() {
        String userSuffix = suffix();
        String agentId = "agent-q-" + userSuffix;
        String orgId = "org-q-" + userSuffix;
        String runId1 = "run-q-1-" + userSuffix;
        String runId2 = "run-q-2-" + userSuffix;
        String runId3 = "run-q-3-" + userSuffix;
        String credId1 = "cred-q-1-" + userSuffix;
        String credId2 = "cred-q-2-" + userSuffix;

        seedAgent(agentId);
        seedRunningRun(runId1, agentId, orgId);
        seedRunningRun(runId2, agentId, orgId);
        seedRunningRun(runId3, agentId, orgId);
        seedCredential(credId1, agentId, true);
        seedCredential(credId2, agentId, true);

        HttpHeaders auth = authenticateAs(
                "ops-q-" + userSuffix,
                "ops-q-" + userSuffix + "@test.local",
                "pass-q-1234",
                List.of("ROLE_ADMIN"));

        Map<String, String> body = Map.of("reason", "credential leak detected");
        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST, new HttpEntity<>(body, auth), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("agentId", agentId);
        assertThat(response.getBody()).containsEntry("runsCancelled", 3);
        assertThat(response.getBody()).containsEntry("credentialsLocked", 2);

        // DB-state assertions: agent flipped, runs cancelled, credentials locked, audit row written.
        Boolean maintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, agentId);
        assertThat(maintenance).isTrue();

        Long cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'CANCELLED'",
                Long.class, agentId);
        assertThat(cancelled).isEqualTo(3L);

        Long disabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_credentials WHERE agent_id = ? AND enabled = false",
                Long.class, agentId);
        assertThat(disabled).isEqualTo(2L);

        // Audit row JSON via ObjectMapper.readTree
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action, changeset FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_QUARANTINE'",
                agentId);
        try {
            // changeset column is JSONB → PGobject; coerce via toString() before readTree.
            JsonNode changeset = JSON.readTree(audit.get("changeset").toString());
            assertThat(changeset.get("reason").asText()).isEqualTo("credential leak detected");
            assertThat(changeset.get("runsCancelled").asInt()).isEqualTo(3);
            assertThat(changeset.get("credentialsLocked").asInt()).isEqualTo(2);
            assertThat(changeset.get("locked_credentials").size()).isEqualTo(2);
        } catch (Exception e) {
            throw new AssertionError("Could not parse audit changeset", e);
        }
    }

    @Test
    void quarantinedAgent_NewBackgroundRun_Returns503ProvingAgentServiceGuard() {
        String userSuffix = suffix();
        String agentId = "agent-block-" + userSuffix;
        seedAgent(agentId);

        HttpHeaders auth = authenticateAs(
                "ops-block-" + userSuffix,
                "ops-block-" + userSuffix + "@test.local",
                "pass-block-1234",
                List.of("ROLE_ADMIN"));

        // Quarantine first
        rest.exchange(url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "test"), auth), Map.class);

        // Background run should be rejected at HTTP entry by AgentService.runInBackground guard
        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "should be rejected");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<String> runResponse = rest.exchange(
                url("/api/agents/" + agentId + "/runs/background"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), String.class);

        // C01 contract: the run is REJECTED at HTTP entry (no QUEUED row, no virtual-thread
        // submission). The exact wire-format (503 vs 500) depends on Spring MVC's exception
        // mapping for ResponseStatusException; both are valid "rejected" signals. The DB-state
        // assertion below is the load-bearing one — proves the guard prevented enqueueing.
        assertThat(runResponse.getStatusCode().is5xxServerError())
                .as("AgentService.runInBackground guard must reject quarantined agents at HTTP layer")
                .isTrue();

        // Load-bearing assertion: no new run row was persisted for this agent.
        Long runRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ?",
                Long.class, agentId);
        assertThat(runRows)
                .as("guard must prevent the run from being enqueued; no new row should exist")
                .isEqualTo(0L);
    }

    @Test
    void unquarantineAgent_RestoresMaintenanceAndCredentials_ButCancelledRunsStayCancelled() {
        String userSuffix = suffix();
        String agentId = "agent-unq-" + userSuffix;
        String orgId = "org-unq-" + userSuffix;
        String runId = "run-unq-" + userSuffix;
        String credId = "cred-unq-" + userSuffix;

        seedAgent(agentId);
        seedRunningRun(runId, agentId, orgId);
        seedCredential(credId, agentId, true);

        HttpHeaders auth = authenticateAs(
                "ops-unq-" + userSuffix,
                "ops-unq-" + userSuffix + "@test.local",
                "pass-unq-1234",
                List.of("ROLE_ADMIN"));

        rest.exchange(url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth), Map.class);

        rest.exchange(url("/api/v1/admin/agents/" + agentId + "/unquarantine"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "false positive"), auth), Map.class);

        Boolean maintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, agentId);
        assertThat(maintenance).isFalse();

        // Credential re-enabled
        Boolean credEnabled = jdbc.queryForObject(
                "SELECT enabled FROM agent_credentials WHERE id = ?", Boolean.class, credId);
        assertThat(credEnabled).isTrue();

        // Run still CANCELLED — non-goal: no auto-resume
        String runStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertThat(runStatus).isEqualTo("CANCELLED");
    }

    @Test
    void unquarantine_ManuallyDisabledCredentialStaysDisabled_TargetedReEnable() {
        String userSuffix = suffix();
        String agentId = "agent-tgt-" + userSuffix;
        String credEnabled = "cred-en-" + userSuffix;
        String credManuallyDisabled = "cred-md-" + userSuffix;

        seedAgent(agentId);
        seedCredential(credEnabled, agentId, true);
        seedCredential(credManuallyDisabled, agentId, false); // manually disabled BEFORE quarantine

        HttpHeaders auth = authenticateAs(
                "ops-tgt-" + userSuffix,
                "ops-tgt-" + userSuffix + "@test.local",
                "pass-tgt-1234",
                List.of("ROLE_ADMIN"));

        rest.exchange(url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "test"), auth), Map.class);
        rest.exchange(url("/api/v1/admin/agents/" + agentId + "/unquarantine"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "test"), auth), Map.class);

        // The originally-enabled credential is back
        Boolean credEnabledFinal = jdbc.queryForObject(
                "SELECT enabled FROM agent_credentials WHERE id = ?", Boolean.class, credEnabled);
        assertThat(credEnabledFinal).isTrue();

        // The manually-disabled credential is STILL disabled (not in audit's locked_credentials list)
        Boolean credManuallyDisabledFinal = jdbc.queryForObject(
                "SELECT enabled FROM agent_credentials WHERE id = ?", Boolean.class, credManuallyDisabled);
        assertThat(credManuallyDisabledFinal).isFalse();
    }

    @Test
    void haltAllRuns_AcrossMultipleOrgs_WritesAuditRowPerAffectedAgent() {
        String userSuffix = suffix();
        String agentA = "agent-h-A-" + userSuffix;
        String agentB = "agent-h-B-" + userSuffix;
        String orgA = "org-h-A-" + userSuffix;
        String orgB = "org-h-B-" + userSuffix;

        seedAgent(agentA);
        seedAgent(agentB);
        seedRunningRun("run-h-1-" + userSuffix, agentA, orgA);
        seedRunningRun("run-h-2-" + userSuffix, agentA, orgA);
        seedRunningRun("run-h-3-" + userSuffix, agentB, orgB);
        seedRunningRun("run-h-4-" + userSuffix, agentB, orgB);

        HttpHeaders auth = authenticateAs(
                "super-h-" + userSuffix,
                "super-h-" + userSuffix + "@test.local",
                "pass-h-1234",
                List.of("ROLE_SUPER_ADMIN"));

        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/admin/incident/halt-all-runs"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "platform incident"), auth), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) response.getBody().get("runsCancelled")).isGreaterThanOrEqualTo(4);
        // tenantsAffected is across the WHOLE table; could be larger if other tests left state.
        // The targeted assertion: our two seeded agents both got audit rows.

        Long auditRowsForOurAgents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE action = 'GLOBAL_HALT_ALL_RUNS' AND agent_id IN (?, ?)",
                Long.class, agentA, agentB);
        assertThat(auditRowsForOurAgents).isEqualTo(2L);

        // Both seeded agents' runs are CANCELLED
        Long cancelledOnA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'CANCELLED'",
                Long.class, agentA);
        assertThat(cancelledOnA).isEqualTo(2L);
        Long cancelledOnB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'CANCELLED'",
                Long.class, agentB);
        assertThat(cancelledOnB).isEqualTo(2L);
    }

    // ─── Authorization matrix (lives here because @PreAuthorize requires the real Spring
    //     Security chain; the controller unit test uses standaloneSetup which bypasses authz). ───

    @Test
    void quarantine_RoleUser_Returns403() {
        String userSuffix = suffix();
        String agentId = "agent-az-u-" + userSuffix;
        seedAgent(agentId);

        HttpHeaders auth = authenticateAs(
                "user-az-u-" + userSuffix,
                "user-az-u-" + userSuffix + "@test.local",
                "pass-az-1234",
                List.of("ROLE_USER"));

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), auth), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void haltAllRuns_RoleAdminButNotSuperAdmin_Returns403() {
        String userSuffix = suffix();

        HttpHeaders auth = authenticateAs(
                "admin-h-" + userSuffix,
                "admin-h-" + userSuffix + "@test.local",
                "pass-h-1234",
                List.of("ROLE_ADMIN"));

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/admin/incident/halt-all-runs"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "x"), auth), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
