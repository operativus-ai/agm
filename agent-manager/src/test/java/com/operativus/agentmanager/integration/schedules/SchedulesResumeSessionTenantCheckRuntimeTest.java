package com.operativus.agentmanager.integration.schedules;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pin {@code Schedule.resumeSessionId} validation contract.
 *
 *   <p>{@code ScheduleService.createSchedule} and {@code updateSchedule} both validate
 *   {@code resumeSessionId} against {@code sessionRepository.existsBySessionIdAndOrgId(
 *   sessionId, callerOrgId)}, parallel to the existing {@code validateTargetOwnership}
 *   guard. A cross-tenant {@code resumeSessionId} is rejected with 400 BAD_REQUEST and
 *   no schedules row is persisted.
 *
 *   <p>Why this matters: at fire time, {@code ScheduleExecutionPoller.executeAndPersist}
 *   binds {@code ScopedValue.where(AgentContextHolder.orgId, schedule.orgId)} and calls
 *   {@code agentOperations.run(targetId, instruction, null, sessionId, …)}. Without the
 *   create-time guard, a cross-tenant {@code sessionId} would either get silently
 *   ignored or — worst case — join the schedule's run output into another tenant's
 *   session row. The create/update guard closes that gap at the API boundary.
 *
 *   <p>This test:
 *   <ul>
 *     <li>Pins the happy-path (same-org resumeSessionId) — accepted, round-trips.</li>
 *     <li>Pins the cross-tenant case — 400 BAD_REQUEST and no row persisted.</li>
 *   </ul>
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesResumeSessionTenantCheckRuntimeTest extends BaseIntegrationTest {

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

    // P3.3-1 — Happy path: resumeSessionId belonging to the caller's own org is
    // accepted and persisted verbatim.
    @Test
    void createSchedule_resumeSessionIdFromCallersOrg_accepted_andRoundTrips() {
        String orgId = "DEFAULT_SYSTEM_ORG";
        HttpHeaders auth = adminAuth("resume-own");
        String agentId = seedAgent("resume-own-agent", orgId);
        String sessionId = seedSession("resume-own-session", orgId, agentId);

        Map<String, Object> body = Map.of(
                "name", "with-own-session",
                "description", "uses caller-owned session",
                "cronExpression", "0 0 12 * * *",
                "targetType", "AGENT",
                "targetId", agentId,
                "resumeSessionId", sessionId,
                "contextualPrompt", "go",
                "isActive", true
        );

        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String scheduleId = (String) created.getBody().get("id");
        assertNotNull(scheduleId);

        String stored = jdbc.queryForObject(
                "SELECT resume_session_id FROM schedules WHERE id = ?",
                String.class, scheduleId);
        assertEquals(sessionId, stored, "same-org resumeSessionId must persist verbatim");
    }

    // P3.3-2 — Cross-tenant resumeSessionId is rejected at create with 400 BAD_REQUEST
    // and no schedules row is persisted. The schedule's targetId (agent) is in the
    // caller's org so validateTargetOwnership passes; the cross-tenant resumeSessionId
    // is what trips validateResumeSessionOwnership.
    @Test
    void createSchedule_resumeSessionIdCrossTenant_returns400_andNoRowPersisted() {
        String orgA = "org-resume-A";
        String orgB = "org-resume-B";
        HttpHeaders authOrgA = adminAuth("resume-cross-orgA", orgA);

        String agentInOrgA = seedAgent("resume-cross-orgA-agent", orgA);
        String sessionInOrgB = seedSession("resume-cross-orgB-session", orgB,
                seedAgent("resume-cross-orgB-agent", orgB));

        Map<String, Object> body = Map.of(
                "name", "with-cross-tenant-session",
                "description", "must be rejected by validateResumeSessionOwnership",
                "cronExpression", "0 0 12 * * *",
                "targetType", "AGENT",
                "targetId", agentInOrgA,
                "resumeSessionId", sessionInOrgB,
                "contextualPrompt", "go",
                "isActive", true
        );

        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM schedules", Long.class);
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST,
                new HttpEntity<>(body, authOrgA), String.class);
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM schedules", Long.class);

        assertAll("cross-tenant resumeSessionId rejected",
                () -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                        "createSchedule must reject cross-tenant resumeSessionId with 400; got "
                                + response.getStatusCode()),
                () -> assertEquals(before, after,
                        "rejected create must not persist a schedules row"));
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-resume-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders adminAuth(String username, String orgId) {
        HttpHeaders headers = adminAuth(username);
        if (!"DEFAULT_SYSTEM_ORG".equals(orgId)) {
            jdbc.update("UPDATE users SET org_id = ? WHERE username = ?", orgId, username);
            return reLogin(username, "pw-resume-1234");
        }
        return headers;
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
                """, agentId, "ResumeSession Test Agent " + label, orgId);
        return agentId;
    }

    private String seedSession(String label, String orgId, String agentId) {
        String sessionId = "session-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, sessionId, label + "-user", orgId, agentId);
        return sessionId;
    }
}
