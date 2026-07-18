package ai.operativus.agentmanager.integration.schedules;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pin {@code Schedule.oneShot} edge-case semantics beyond
 *   {@link SchedulesRuntimeTest}'s natural-fire happy path
 *   ({@code oneShotSchedule_disablesItselfAfterFirstFire}).
 *
 *   Production semantic (per {@code ScheduleExecutionPoller.triggerScheduleExecution}
 *   docstring + line ~160):
 *   <ul>
 *     <li>The {@code is_active=false} flip happens in the same OUTER transaction as
 *         the {@code schedule_run} INSERT — at DISPATCH time, before the async VT
 *         spawns.</li>
 *     <li>This means oneShot disables on FIRST DISPATCH regardless of whether the run
 *         eventually succeeds or fails. A retry-on-FAILED semantic is NOT a feature —
 *         oneShot is genuinely fire-once.</li>
 *     <li>{@code manualTrigger} also goes through {@code triggerScheduleExecution}, so
 *         oneShot is respected on manual trigger as well as natural cron fire.</li>
 *   </ul>
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SchedulesOneShotEdgeCasesRuntimeTest extends BaseIntegrationTest {

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // P2.3-1 — Manual trigger respects oneShot. POST /trigger on a oneShot=true schedule
    // fires once and immediately disables the schedule. Pinning this prevents a future
    // bug where manualTrigger bypasses the oneShot flip (e.g. an "override" semantic
    // that gets too aggressive).
    @Test
    void oneShotTrue_manualTrigger_firesAndDisablesScheduleSynchronously() {
        HttpHeaders auth = adminAuth("oneshot-manual");
        String agentId = seedAgent("oneshot-manual-agent");
        String scheduleId = seedOneShotScheduleViaJdbc("oneshot-manual-sched", agentId);

        // Sanity precondition
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, scheduleId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT one_shot FROM schedules WHERE id = ?", Boolean.class, scheduleId));

        ResponseEntity<java.util.Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth),
                new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {});
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertAll("oneShot disables on manual trigger",
                        () -> assertEquals(1, runCount(scheduleId),
                                "schedule_runs must contain exactly one row — fire-once"),
                        () -> assertEquals(Boolean.FALSE, jdbc.queryForObject(
                                "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, scheduleId),
                                "is_active must flip to false on the same transaction as the run insert")));
    }

    // P2.3-2 — The disable flip is atomic with the schedule_run insert. After dispatch,
    // a caller observing the DB sees BOTH "is_active=false" AND "1 schedule_runs row"
    // OR NEITHER. They never see the run row without the flip (which would let the next
    // poller tick fire again). Per the docstring: "flip + insert atomicity guarantees
    // the next tick will not observe a still-active one-shot row".
    @Test
    void oneShotTrue_disableFlipIsAtomicWithScheduleRunInsert() {
        HttpHeaders auth = adminAuth("oneshot-atomic");
        String agentId = seedAgent("oneshot-atomic-agent");
        String scheduleId = seedOneShotScheduleViaJdbc("oneshot-atomic-sched", agentId);

        rest.exchange(url("/api/v1/schedules/" + scheduleId + "/trigger"),
                HttpMethod.POST, new HttpEntity<>(auth), String.class);

        // Both conditions must converge to the "fire-once" state — never an
        // intermediate where the run exists but the schedule is still active.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    int runs = runCount(scheduleId);
                    Boolean active = jdbc.queryForObject(
                            "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, scheduleId);
                    // The atomicity contract: once a run row exists, the schedule MUST
                    // be inactive (the outer transaction either committed both or neither).
                    if (runs > 0) {
                        assertEquals(Boolean.FALSE, active,
                                "ATOMICITY VIOLATION: schedule_runs row exists (count=" + runs
                                        + ") but schedule.is_active=" + active
                                        + " — the next poll tick would re-fire the one-shot");
                    }
                    // Drive the assertion to completion: require 1 run + false active.
                    assertEquals(1, runs, "expecting exactly one run post-trigger");
                    assertEquals(Boolean.FALSE, active, "expecting disabled post-trigger");
                });
    }

    // ─── helpers ───

    private HttpHeaders adminAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pw-oneshot-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String seedAgent(String label) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, 'DEFAULT_SYSTEM_ORG', now(), now())
                """, agentId, "OneShot Test Agent " + label);
        return agentId;
    }

    private String seedOneShotScheduleViaJdbc(String label, String targetId) {
        String scheduleId = "schedule-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id,
                                       is_active, one_shot, contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '0 0 12 * * *', 'AGENT', ?, true, true, ?, 'DEFAULT_SYSTEM_ORG', now(), now())
                """,
                scheduleId, "sched-" + label, "OneShot edges " + label,
                targetId, "Run scheduled task " + label);
        return scheduleId;
    }

    private int runCount(String scheduleId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Integer.class, scheduleId);
        return n == null ? 0 : n;
    }
}
