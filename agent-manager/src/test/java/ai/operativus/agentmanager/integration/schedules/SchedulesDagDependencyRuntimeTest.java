package ai.operativus.agentmanager.integration.schedules;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.SchedulerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Pin the {@code Schedule.dependsOnScheduleId} DAG dependency
 *   contract (F6). The poller in {@code ScheduleExecutionPoller.isScheduleDue} gates
 *   firing on the parent's most-recent {@code schedule_runs} status being COMPLETED;
 *   {@code ScheduleService.createSchedule}/{@code updateSchedule} reject self-references
 *   and cycles at validation time with 400 BAD_REQUEST.
 *
 *   Semantics:
 *   <ul>
 *     <li>No declared parent → fire on cron timing (legacy behaviour preserved).</li>
 *     <li>Declared parent, parent has no runs yet → block.</li>
 *     <li>Declared parent, parent's most-recent run = FAILED / CANCELLED / RUNNING → block.</li>
 *     <li>Declared parent, parent's most-recent run = COMPLETED → fire on cron timing.</li>
 *     <li>Cycle (self-ref, A→B→A, etc.) → reject at create/update with 400.</li>
 *   </ul>
 *
 * Test-time scheduler control: {@code SchedulerTestSupport.tickSchedulePoll()} drives
 * the poller deterministically; production tick is pinned to 86_400_000 ms in
 * {@code application-test.properties} so the poller does not self-fire mid-test.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class,
        SchedulerTestSupport.class})
public class SchedulesDagDependencyRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private SchedulerTestSupport scheduler;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P1.2-1 — Schedule B with depends_on=A does NOT fire while A has no run.
    // Both schedules are active and would fire on cron timing (every minute);
    // only the dependency gate blocks B.
    @Test
    void scheduleBwithDependsOnA_doesNotFire_whileAHasNoCompletedRun() {
        String agentId = seedAgent("dag-1");
        String aId = seedSchedule("a", "0 * * * * *", agentId, null);
        String bId = seedSchedule("b", "0 * * * * *", agentId, aId);

        scheduler.tickSchedulePoll();

        assertAll("first tick: A fires, B blocked",
                () -> assertEquals(1L, countRuns(aId),
                        "A has no parent — must fire on first tick"),
                () -> assertEquals(0L, countRuns(bId),
                        "B's parent A has no COMPLETED run yet — must NOT fire"));
    }

    // P1.2-2 — Once A's most-recent run is COMPLETED, B fires on the next tick
    // (cron is permissive — every minute — so the dependency gate is the only
    // gate that can block B).
    @Test
    void scheduleBwithDependsOnA_firesAfterAcompletes() {
        String agentId = seedAgent("dag-2");
        String aId = seedSchedule("a", "0 * * * * *", agentId, null);
        String bId = seedSchedule("b", "0 * * * * *", agentId, aId);

        // Insert a COMPLETED run for A directly so the gate is open without
        // depending on the actual agent execution path.
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output, error_message)
                VALUES (?, ?, 'COMPLETED', ?, ?, '{}'::jsonb, NULL)
                """,
                "run-dag-2-a-" + UUID.randomUUID(), aId,
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusMinutes(2).plusSeconds(5));

        scheduler.tickSchedulePoll();

        assertEquals(1L, countRuns(bId),
                "with parent A's most-recent run COMPLETED, B's dependency gate must open and B must fire");
    }

    // P1.2-3 — A's most-recent run is FAILED. B must NOT fire — the gate requires
    // COMPLETED, not just any terminal status. A FAILED parent that unblocked its
    // dependent would be almost certainly wrong UX.
    @Test
    void scheduleBwithDependsOnA_doesNotFire_whileLastAIsFailed() {
        String agentId = seedAgent("dag-3");
        String aId = seedSchedule("a", "0 * * * * *", agentId, null);
        String bId = seedSchedule("b", "0 * * * * *", agentId, aId);

        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output, error_message)
                VALUES (?, ?, 'FAILED', ?, ?, NULL, 'simulated upstream failure')
                """,
                "run-dag-3-a-" + UUID.randomUUID(), aId,
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusMinutes(2).plusSeconds(5));

        scheduler.tickSchedulePoll();

        assertEquals(0L, countRuns(bId),
                "FAILED upstream must NOT unblock the dependent — gate stays closed");
    }

    // P1.2-4 — Cycle detection at create/update time. Self-reference + multi-hop
    // cycles must reject with 400 BAD_REQUEST and leave the row state unchanged.
    @Test
    void cyclicDependency_rejectsAt400() {
        String agentId = seedAgent("dag-4");
        HttpHeaders auth = adminAuth("dag-cycle");

        // Create A via the API so cycle detection sees a persisted row to walk.
        String aId = createSchedule(auth, "cycle-A", agentId, null);
        // Create B → A, C → B.
        String bId = createSchedule(auth, "cycle-B", agentId, aId);
        String cId = createSchedule(auth, "cycle-C", agentId, bId);

        // Self-reference: PUT A with depends_on=A → 400.
        Map<String, Object> selfLoop = updateBody("cycle-A-self", agentId, aId);
        ResponseEntity<String> selfResp = rest.exchange(
                url("/api/v1/schedules/" + aId), HttpMethod.PUT,
                new HttpEntity<>(selfLoop, auth), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, selfResp.getStatusCode(),
                "self-referencing dependency must 400");

        // Cycle: PUT A with depends_on=C closes A → B → C → A.
        Map<String, Object> cycleClose = updateBody("cycle-A-loop", agentId, cId);
        ResponseEntity<String> cycleResp = rest.exchange(
                url("/api/v1/schedules/" + aId), HttpMethod.PUT,
                new HttpEntity<>(cycleClose, auth), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, cycleResp.getStatusCode(),
                "A→B→C→A cycle must 400");

        // A's row state must be unchanged (no depends_on stamped).
        String storedDep = jdbc.queryForObject(
                "SELECT depends_on_schedule_id FROM schedules WHERE id = ?", String.class, aId);
        assertEquals(null, storedDep,
                "A's depends_on must remain unset after both rejected PUTs; got " + storedDep);
        // Sanity: B and C still link as the happy-path created them.
        assertEquals(aId, jdbc.queryForObject(
                "SELECT depends_on_schedule_id FROM schedules WHERE id = ?", String.class, bId));
        assertEquals(bId, jdbc.queryForObject(
                "SELECT depends_on_schedule_id FROM schedules WHERE id = ?", String.class, cId));
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-dag-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String seedAgent(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "DAG Test Agent " + label);
        return agentId;
    }

    /** Seeds a schedule row directly via JDBC so tests can set depends_on_schedule_id without going through the API. */
    private String seedSchedule(String label, String cron, String targetId, String dependsOnId) {
        String id = "sched-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, contextual_prompt, org_id, depends_on_schedule_id,
                                       created_at, updated_at)
                VALUES (?, ?, 'DAG test', ?, 'AGENT', ?, true, 'Run scheduled task', 'DEFAULT_SYSTEM_ORG',
                        ?, now(), now())
                """, id, label, cron, targetId, dependsOnId);
        return id;
    }

    /** Creates a schedule via the API so cycle detection has live persisted state to walk. */
    private String createSchedule(HttpHeaders auth, String name, String targetId, String dependsOnId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "DAG cycle test");
        body.put("cronExpression", "0 0 12 * * *");
        body.put("targetType", "AGENT");
        body.put("targetId", targetId);
        body.put("contextualPrompt", "x");
        body.put("isActive", true);
        if (dependsOnId != null) body.put("dependsOnScheduleId", dependsOnId);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules"), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createSchedule fixture must succeed; got " + resp.getStatusCode() + " body=" + resp.getBody());
        assertNotNull(resp.getBody());
        return (String) resp.getBody().get("id");
    }

    private Map<String, Object> updateBody(String name, String targetId, String dependsOnId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "DAG cycle test PUT");
        body.put("cronExpression", "0 0 12 * * *");
        body.put("targetType", "AGENT");
        body.put("targetId", targetId);
        body.put("contextualPrompt", "x");
        body.put("isActive", true);
        body.put("dependsOnScheduleId", dependsOnId);
        return body;
    }

    private long countRuns(String scheduleId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?", Long.class, scheduleId);
        return c == null ? 0L : c;
    }
}
