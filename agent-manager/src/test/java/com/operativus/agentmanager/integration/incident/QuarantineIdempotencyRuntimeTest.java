package com.operativus.agentmanager.integration.incident;

import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins the idempotency branches of
 *   {@code IncidentResponseService.quarantineAgent} and
 *   {@code IncidentResponseService.unquarantineAgent} — the two cases where the service
 *   short-circuits because the agent is already in the requested state.
 *
 *   <p><b>Why this is a separate file from {@code QuarantineLifecycleIntegrationTest}</b>:
 *   that test pins the FULL round-trip (cancel-runs + lock-credentials + audit + re-enable),
 *   the run-start guard, targeted-credential re-enable, global halt, and the authz matrix.
 *   The two idempotency branches were the remaining net-new gap surfaced by the C01 audit
 *   recon — kept in their own file for greppability and PR-scope clarity.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Re-quarantine of an already-quarantined agent</b> — service returns
 *         {@code alreadyQuarantined=true} + {@code runsCancelled=0} + {@code credentialsLocked=0}.
 *         No new audit row is written (audit is a state-transition record, not a
 *         per-request record).</li>
 *     <li><b>Unquarantine of a non-quarantined agent</b> — service no-ops via the
 *         {@code unquarantineNoop} branch, returns 200 with zero side-effect counts.</li>
 *   </ul>
 *
 *   <p>Why "no new audit row" matters: re-quarantine spam would otherwise inflate the audit
 *   trail with duplicate AGENT_QUARANTINE rows from network-retry loops, masking the real
 *   state-transition events when forensic review needs them.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class QuarantineIdempotencyRuntimeTest extends BaseIntegrationTest {

    private String suffix() {
        return Long.toHexString(System.nanoTime());
    }

    private void seedAgent(String agentId, boolean maintenanceMode) {
        jdbc.update("""
                INSERT INTO agents (id, name, active, security_tier, compliance_tier,
                                    maintenance_mode, version, org_id, created_at, updated_at)
                VALUES (?, ?, true, 1, 'TIER_1_STANDARD', ?, 0, ?, NOW(), NOW())
                """, agentId, agentId, maintenanceMode, TenantConstants.DEFAULT_SYSTEM_ORG);
    }

    @Test
    void reQuarantineOfAlreadyQuarantinedAgentIsNoOpAndWritesNoNewAuditRow() {
        String userSuffix = suffix();
        String agentId = "agent-q-idem-" + userSuffix;
        seedAgent(agentId, /* maintenanceMode = */ true);

        HttpHeaders auth = authenticateAs(
                "ops-q-idem-" + userSuffix,
                "ops-q-idem-" + userSuffix + "@test.local",
                "pass-qi-1234",
                List.of("ROLE_ADMIN"));

        Long auditRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_QUARANTINE'",
                Long.class, agentId);

        Map<String, String> body = Map.of("reason", "duplicate submit from FE retry");
        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/admin/agents/" + agentId + "/quarantine"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map rb = response.getBody();
        assertThat(rb).containsEntry("agentId", agentId);
        assertThat(rb).containsEntry("alreadyQuarantined", true);
        assertThat(rb).containsEntry("runsCancelled", 0);
        assertThat(rb).containsEntry("credentialsLocked", 0);

        // The maintenance flag was true before AND after — idempotent on DB state.
        Boolean maintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, agentId);
        assertThat(maintenance).isTrue();

        // No NEW AGENT_QUARANTINE audit row — re-quarantine doesn't pollute the audit trail.
        Long auditRowsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_QUARANTINE'",
                Long.class, agentId);
        assertThat(auditRowsAfter)
                .as("re-quarantine of an already-quarantined agent must NOT write a new "
                        + "AGENT_QUARANTINE audit row (audit is a state-transition record, "
                        + "not a per-request record)")
                .isEqualTo(auditRowsBefore);
    }

    @Test
    void unquarantineOfNonQuarantinedAgentIsNoOp() {
        String userSuffix = suffix();
        String agentId = "agent-uq-idem-" + userSuffix;
        seedAgent(agentId, /* maintenanceMode = */ false);

        HttpHeaders auth = authenticateAs(
                "ops-uq-idem-" + userSuffix,
                "ops-uq-idem-" + userSuffix + "@test.local",
                "pass-uqi-1234",
                List.of("ROLE_ADMIN"));

        Long auditRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_UNQUARANTINE'",
                Long.class, agentId);

        Map<String, String> body = Map.of("reason", "checking idempotency of non-quarantined agent");
        ResponseEntity<Map> response = rest.exchange(
                url("/api/v1/admin/agents/" + agentId + "/unquarantine"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map rb = response.getBody();
        assertThat(rb).containsEntry("agentId", agentId);
        assertThat(rb).containsEntry("runsCancelled", 0);
        assertThat(rb).containsEntry("credentialsLocked", 0);

        // Maintenance flag unchanged (was false, stays false).
        Boolean maintenance = jdbc.queryForObject(
                "SELECT maintenance_mode FROM agents WHERE id = ?", Boolean.class, agentId);
        assertThat(maintenance).isFalse();

        // No NEW AGENT_UNQUARANTINE audit row — noop branch short-circuits before the
        // audit save.
        Long auditRowsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audits WHERE agent_id = ? AND action = 'AGENT_UNQUARANTINE'",
                Long.class, agentId);
        assertThat(auditRowsAfter)
                .as("unquarantine of a non-quarantined agent must NOT write a new "
                        + "AGENT_UNQUARANTINE audit row")
                .isEqualTo(auditRowsBefore);
    }
}
