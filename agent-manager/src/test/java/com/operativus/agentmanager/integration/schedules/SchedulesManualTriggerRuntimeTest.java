package com.operativus.agentmanager.integration.schedules;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pin {@code POST /api/v1/schedules/{id}/trigger} contract.
 *   The endpoint forwards to {@code ScheduleExecutionPoller.manualTrigger} which is
 *   tenant-scoped via {@code findByIdAndOrgId(scheduleId, callerOrgId)} — cross-tenant
 *   trigger attempts return Optional.empty and the controller still returns 202
 *   ACCEPTED with the same body shape (intentional — no tenant-membership leak via
 *   response). Manual trigger does NOT consult {@code Schedule.isActive}, so disabled
 *   schedules ARE manually-fireable (override semantic), and the trigger does NOT
 *   reactivate the schedule (no implicit isActive flip).
 *
 *   {@link SchedulesRuntimeTest} exercises trigger only transitively via fixture
 *   helpers. This test pins the trigger endpoint's contracts directly:
 *   <ul>
 *     <li>ADMIN happy path → 202 + a schedule_runs row appears</li>
 *     <li>ROLE_USER → 403 + zero schedule_runs rows</li>
 *     <li>Cross-tenant id → 202 (silent drop, no info leak) + zero rows</li>
 *     <li>Disabled schedule → 202 + row appears AND isActive stays false</li>
 *   </ul>
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesManualTriggerRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P1.3-1 — ADMIN happy path. POST /trigger → 202 ACCEPTED + body marker + a
    // schedule_runs row appears for the schedule (created synchronously inside
    // triggerScheduleExecution before the async VT spawns).
    @Test
    void triggerSchedule_admin_happyPath_returns202_andCreatesScheduleRunRow() {
        HttpHeaders auth = adminAuth("trig-admin");
        String agentId = seedAgent("trig-admin-agent", "DEFAULT_SYSTEM_ORG");
        String scheduleId = seedScheduleViaJdbc("trig-admin-sched", agentId,
                "0 0 12 * * *", true, "DEFAULT_SYSTEM_ORG");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertAll("admin trigger contract",
                () -> assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                        "POST /trigger returns 202, not 200 — the dispatch is async"),
                () -> assertEquals("Schedule execution triggered.", resp.getBody().get("message"),
                        "response body must carry the documented marker for FE / operator tooling"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertEquals(1,
                        runCountForSchedule(scheduleId),
                        "schedule_runs must contain exactly one row for this schedule after trigger"));
    }

    // P1.3-2 — RBAC: ROLE_USER must hit the method-level @PreAuthorize("hasRole('ADMIN')")
    // and 403. No schedule_runs row may be created (rejected before the service).
    @Test
    void triggerSchedule_roleUser_returns403_noScheduleRunCreated() {
        HttpHeaders userAuth = roleUserAuth("trig-user");
        String agentId = seedAgent("trig-user-agent", "DEFAULT_SYSTEM_ORG");
        String scheduleId = seedScheduleViaJdbc("trig-user-sched", agentId,
                "0 0 12 * * *", true, "DEFAULT_SYSTEM_ORG");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(userAuth), JSON_MAP);

        assertAll("RBAC gate rejects ROLE_USER",
                () -> assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode()),
                () -> assertEquals(0, runCountForSchedule(scheduleId),
                        "rejected requests must not create schedule_runs — rejection is method-level @PreAuthorize, precedes service"));
    }

    // P1.3-3 — Cross-tenant: caller in org-A, schedule in org-B. Endpoint returns 202
    // (same as happy path — intentional, no tenant-membership leak via response) but
    // manualTrigger's findByIdAndOrgId returns empty and no schedule_runs row is
    // created. This is the load-bearing anti-enumeration contract for the trigger
    // endpoint.
    @Test
    void triggerSchedule_crossTenantSchedule_returns202_silentDrop_noScheduleRunCreated() {
        String orgA = "org-trig-A";
        String orgB = "org-trig-B";
        HttpHeaders authOrgA = adminAuth("trig-cross-tenant-orgA-caller", orgA);

        String agentInOrgB = seedAgent("trig-cross-orgB-agent", orgB);
        String scheduleInOrgB = seedScheduleViaJdbc("trig-cross-orgB-sched", agentInOrgB,
                "0 0 12 * * *", true, orgB);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + scheduleInOrgB + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(authOrgA), JSON_MAP);

        assertAll("cross-tenant trigger silently drops",
                () -> assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                        "endpoint returns 202 even on cross-tenant — same response as happy path so "
                                + "attacker cannot probe schedule-id existence across tenants via the trigger endpoint"),
                () -> assertEquals(0, runCountForSchedule(scheduleInOrgB),
                        "org-B's schedule must NOT have been triggered — the manualTrigger findByIdAndOrgId "
                                + "guard returns Optional.empty for cross-tenant ids"));
    }

    // P1.3-4 — Disabled-override semantic. The poller's natural fire path checks
    // isActive (findByIsActiveTrue…), but manualTrigger fires REGARDLESS of isActive.
    // After a manual trigger on a disabled schedule, the row stays isActive=false —
    // manual fire does not implicitly reactivate. This semantic lets operators "test
    // a paused schedule once" without resuming the natural cron cadence.
    @Test
    void triggerSchedule_disabledSchedule_stillFires_andDoesNotReactivate() {
        HttpHeaders auth = adminAuth("trig-disabled");
        String agentId = seedAgent("trig-disabled-agent", "DEFAULT_SYSTEM_ORG");
        String scheduleId = seedScheduleViaJdbc("trig-disabled-sched", agentId,
                "0 0 12 * * *", /*active=*/false, "DEFAULT_SYSTEM_ORG");

        // Sanity precondition
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, scheduleId));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertEquals(1,
                        runCountForSchedule(scheduleId),
                        "disabled schedule must still fire under manual trigger — override semantic"));

        assertNotEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, scheduleId),
                "manual trigger must NOT reactivate the schedule — isActive stays false");
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-trig-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders adminAuth(String username, String orgId) {
        HttpHeaders headers = adminAuth(username);
        if (!"DEFAULT_SYSTEM_ORG".equals(orgId)) {
            jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, username);
            return reLogin(username, "pw-trig-1234");
        }
        return headers;
    }

    private HttpHeaders roleUserAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-trig-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders reLogin(String username, String password) {
        var login = new com.operativus.agentmanager.core.model.AuthModels.LoginRequest(username, password);
        ResponseEntity<com.operativus.agentmanager.core.model.AuthModels.JwtResponse> response =
                rest.postForEntity(url("/api/auth/login"), login,
                        com.operativus.agentmanager.core.model.AuthModels.JwtResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(response.getBody().token());
        return headers;
    }

    private String seedAgent(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Manual Trigger Test Agent " + label, orgId);
        return agentId;
    }

    private String seedScheduleViaJdbc(String label, String targetId, String cron, boolean active, String orgId) {
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AGENT', ?, ?, ?, ?, now(), now())
                """,
                scheduleId, "sched-" + label, "trigger-test " + label,
                cron, targetId, active, "Run scheduled task " + label, orgId);
        return scheduleId;
    }

    private int runCountForSchedule(String scheduleId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);
        return n == null ? 0 : n;
    }
}
