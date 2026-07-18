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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pin {@code ScheduleService.updateSchedule} behavior with
 *   respect to in-flight {@code schedule_runs} rows AND stale-version PUTs.
 *
 *   {@code SchedulesRuntimeTest} covers basic update via its round-trip / mutation
 *   tests, but no test explicitly pins:
 *   <ul>
 *     <li>That a PUT to {@code /schedules/{id}} during an in-flight run does NOT
 *         mutate the {@code schedule_runs} row — update is row-scoped to the
 *         {@code schedules} table only.</li>
 *     <li>Client-known-version conflict detection. {@code Schedule} has a
 *         {@code @Version} column (changeset 069) and {@code ScheduleService.updateSchedule}
 *         pre-checks the inbound DTO version against the loaded entity. A stale
 *         version surfaces as {@code ObjectOptimisticLockingFailureException} → 409
 *         via {@code GlobalExceptionHandler}.</li>
 *   </ul>
 *
 *   This test does NOT pin "next-fire-uses-new-cron" because verifying which cron
 *   the poller picks requires deterministic clock injection — the poller calls
 *   {@code ZonedDateTime.now(...)} directly. That contract is verified indirectly
 *   by {@code SchedulesRuntimeTest.pollerFiresActiveSchedule_…}; a dedicated cron-
 *   swap test would need the SchedulerTestSupport time-travel hooks.
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesUpdateMidFireRuntimeTest extends BaseIntegrationTest {

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

    // P2.1-1 — Update during in-flight run does NOT mutate the in-flight schedule_runs
    // row. The PUT only touches the schedules table; the in-flight run completes
    // independently with whatever cron / target / prompt was in effect at dispatch.
    @Test
    void updateSchedule_whileScheduleRunIsRunning_doesNotMutateInFlightRunRow() {
        HttpHeaders auth = adminAuth("update-mid-fire-running");
        Fixture fx = seedSchedule("update-mid-fire-running", "0 0 12 * * *",
                "Daily report at noon");

        // Simulate an in-flight run row directly. This is what triggerScheduleExecution
        // would have INSERTed before spawning the VT; we're scoping the test to the
        // update path, not the dispatch path.
        String runId = "run-mid-fire-" + UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(5);
        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output)
                VALUES (?, ?, 'RUNNING', ?, NULL, NULL)
                """, runId, fx.scheduleId, startedAt);

        // Capture the in-flight row's state to compare after the update.
        Map<String, Object> rowBefore = jdbc.queryForMap(
                "SELECT status, started_at, completed_at FROM schedule_runs WHERE id = ?", runId);

        Map<String, Object> updateBody = Map.of(
                "name", "now-runs-hourly",
                "description", "renamed mid-fire",
                "cronExpression", "0 0 * * * *",  // hourly instead of daily
                "targetType", "AGENT",
                "targetId", fx.agentId,
                "contextualPrompt", "New prompt",
                "isActive", true,
                // F3 — seedSchedule inserts via JDBC so version defaults to 0.
                "version", 0
        );

        ResponseEntity<Map<String, Object>> putResp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId), HttpMethod.PUT,
                new HttpEntity<>(updateBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, putResp.getStatusCode());

        // Schedule row reflects the new state.
        Map<String, Object> scheduleAfter = jdbc.queryForMap(
                "SELECT cron_expression, name, contextual_prompt FROM schedules WHERE id = ?",
                fx.scheduleId);
        assertEquals("0 0 * * * *", scheduleAfter.get("cron_expression"));
        assertEquals("now-runs-hourly", scheduleAfter.get("name"));

        // In-flight schedule_runs row UNCHANGED.
        Map<String, Object> rowAfter = jdbc.queryForMap(
                "SELECT status, started_at, completed_at FROM schedule_runs WHERE id = ?", runId);

        assertAll("in-flight run untouched by schedule update",
                () -> assertEquals(rowBefore.get("status"), rowAfter.get("status"),
                        "in-flight RUNNING status must not be cancelled / mutated by PUT to schedules"),
                () -> assertEquals(rowBefore.get("started_at"), rowAfter.get("started_at"),
                        "started_at must remain unchanged — operator expects in-flight to complete naturally"),
                () -> assertEquals(rowBefore.get("completed_at"), rowAfter.get("completed_at"),
                        "completed_at stays null (still running) — update doesn't force-terminate"));
    }

    // P2.1-2 — Stale-version PUT returns 409 and the row is unchanged. After the F3
    // @Version column on schedules, ScheduleService.updateSchedule pre-checks the
    // inbound DTO version against the loaded entity's version; mismatch throws
    // ObjectOptimisticLockingFailureException → 409 via GlobalExceptionHandler.
    //
    // Sequence: two PUTs with version=0. The first commits and bumps version to 1.
    // The second re-uses the stale version=0 (simulating a client that read once,
    // missed the update, and tried to write again) — it must 409, NOT silently
    // overwrite the first writer's state.
    @Test
    void stalePut_returns409Conflict_andRowNotMutated() {
        HttpHeaders auth = adminAuth("update-stale");
        Fixture fx = seedSchedule("update-stale", "0 0 12 * * *", "initial");

        // Both updates declare the same stale version=0 — the seed inserts via JDBC,
        // so the row starts at version=0.
        Map<String, Object> firstUpdate = Map.of(
                "name", "first-writer",
                "description", "first-writer",
                "cronExpression", "0 0 6 * * *",
                "targetType", "AGENT",
                "targetId", fx.agentId,
                "contextualPrompt", "first",
                "isActive", true,
                "version", 0
        );
        Map<String, Object> staleSecondUpdate = Map.of(
                "name", "stale-writer",
                "description", "stale-writer",
                "cronExpression", "0 0 18 * * *",
                "targetType", "AGENT",
                "targetId", fx.agentId,
                "contextualPrompt", "stale",
                "isActive", true,
                "version", 0
        );

        ResponseEntity<Map<String, Object>> firstResp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId), HttpMethod.PUT,
                new HttpEntity<>(firstUpdate, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, firstResp.getStatusCode(),
                "first PUT with current version must succeed");
        assertEquals(1, ((Number) firstResp.getBody().get("version")).longValue(),
                "save must bump version 0 → 1");

        ResponseEntity<String> staleResp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId), HttpMethod.PUT,
                new HttpEntity<>(staleSecondUpdate, auth), String.class);
        assertEquals(HttpStatus.CONFLICT, staleResp.getStatusCode(),
                "stale PUT (version=0 after row advanced to 1) must return 409, NOT 200");

        Map<String, Object> finalRow = jdbc.queryForMap(
                "SELECT name, cron_expression, contextual_prompt, version FROM schedules WHERE id = ?",
                fx.scheduleId);
        assertAll("row reflects first-writer only; stale PUT had no effect",
                () -> assertEquals("first-writer", finalRow.get("name"),
                        "stale PUT must not overwrite the first writer's name"),
                () -> assertEquals("0 0 6 * * *", finalRow.get("cron_expression")),
                () -> assertEquals("first", finalRow.get("contextual_prompt")),
                () -> assertEquals(1L, ((Number) finalRow.get("version")).longValue(),
                        "version must be 1 (first writer's bump); stale PUT did not increment"));
    }

    // P2.1-3 — PUT without a version field is rejected with 400 (version is required).
    @Test
    void putWithoutVersion_returns400() {
        HttpHeaders auth = adminAuth("update-no-version");
        Fixture fx = seedSchedule("update-no-version", "0 0 12 * * *", "initial");

        Map<String, Object> updateBody = Map.of(
                "name", "missing-version",
                "description", "no version supplied",
                "cronExpression", "0 0 6 * * *",
                "targetType", "AGENT",
                "targetId", fx.agentId,
                "contextualPrompt", "x",
                "isActive", true
        );

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/schedules/" + fx.scheduleId), HttpMethod.PUT,
                new HttpEntity<>(updateBody, auth), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "PUT without version must 400 — the ETag-style contract is mandatory");

        String stillName = jdbc.queryForObject(
                "SELECT name FROM schedules WHERE id = ?", String.class, fx.scheduleId);
        assertEquals("sched-update-no-version", stillName,
                "rejected PUT must not have mutated the row");
    }

    // ─── helpers ───

    private record Fixture(String agentId, String scheduleId) {}

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-update-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private Fixture seedSchedule(String label, String cron, String description) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "UpdateMidFire Agent " + label);

        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AGENT', ?, true, ?, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, scheduleId, "sched-" + label, description, cron, agentId,
                "Initial prompt");
        return new Fixture(agentId, scheduleId);
    }
}
