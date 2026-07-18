package com.operativus.agentmanager.integration.workflows;

import com.operativus.agentmanager.control.service.ScheduleExecutionPoller;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the WORKFLOW target-type branch of
 *   {@link ScheduleExecutionPoller#triggerScheduleExecution} — the integration seam between
 *   the scheduling subsystem and the workflow execution engine. Verifies that:
 *   <ul>
 *     <li>A schedule with {@code target_type='WORKFLOW'} fires the workflow execution and
 *         creates a {@code workflow_runs} row linked to a {@code schedule_runs} row via
 *         {@code workflow_run_id}.</li>
 *     <li>One-shot WORKFLOW schedules self-disable ({@code is_active=false}) after first
 *         dispatch — the T037-3 contract that guarantees exactly-once firing even before
 *         the VT has begun executing.</li>
 *     <li>{@code manualTrigger} is tenant-scoped — a cross-tenant trigger is silently
 *         dropped (no schedule_run row created), matching the
 *         {@code findByIdAndOrgId(scheduleId, resolvedOrgId)} guard.</li>
 *   </ul>
 *
 *   <p>Existing {@code SchedulesRuntimeTest} covers AGENT/TEAM target dispatch + schedule
 *   CRUD + cron evaluation; the WORKFLOW path was previously untested. This class fills
 *   that gap without duplicating the AGENT-path coverage.
 *
 *   <p>The poller's {@code @Scheduled} cron is pushed to 24h in
 *   {@code application-test.properties} (interval property
 *   {@code agentmanager.scheduler.schedule-poll-ms}); each case invokes
 *   {@link ScheduleExecutionPoller#manualTrigger} directly. {@code manualTrigger} flows
 *   through the same {@code triggerScheduleExecution} that the cron uses, so the dispatch
 *   contract is identical.
 *
 *   <p>Async tail: {@code triggerScheduleExecution} inserts the {@code schedule_runs} row
 *   synchronously, then dispatches a VT to {@code executeAndPersist}. The VT calls
 *   {@code WorkflowService.executeWorkflowAsync} which itself inserts a {@code workflow_runs}
 *   row synchronously before launching another VT for the per-step execution loop. So the
 *   linkage assertion needs Awaitility to wait for the dispatch VT to populate
 *   {@code schedule_runs.workflow_run_id}. We do NOT wait for the workflow itself to reach
 *   a terminal state — that's covered separately and adds flake surface here.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowScheduledExecutionRuntimeTest extends BaseIntegrationTest {

    @Autowired private ScheduleExecutionPoller poller;

    @BeforeEach
    void seedModelRow() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void manualTrigger_workflowSchedule_dispatchesWorkflowRun_andLinksScheduleRun() {
        Fixture f = seedFixture("dispatch", /*oneShot=*/ false);

        manualTriggerAs(f.orgId, f.scheduleId);

        // Wait for the dispatch VT to populate schedule_runs.workflow_run_id.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> workflowRunIdOnScheduleRun(f.scheduleId) != null);

        String workflowRunId = workflowRunIdOnScheduleRun(f.scheduleId);
        Integer wfRunCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE id = ?", Integer.class, workflowRunId);
        String scheduleRunStatus = jdbc.queryForObject(
                "SELECT status FROM schedule_runs WHERE schedule_id = ?", String.class, f.scheduleId);
        String linkedWorkflowId = jdbc.queryForObject(
                "SELECT workflow_id FROM workflow_runs WHERE id = ?", String.class, workflowRunId);

        assertAll("WORKFLOW schedule dispatch contract",
                () -> assertNotNull(workflowRunId,
                        "schedule_runs.workflow_run_id must be populated post-dispatch"),
                () -> assertEquals(1, wfRunCount,
                        "exactly one workflow_runs row must be created for the dispatched run; "
                                + "got " + wfRunCount),
                () -> assertEquals("RUNNING", scheduleRunStatus,
                        "schedule_runs.status must remain RUNNING until "
                                + "syncWorkflowScheduleRuns() mirrors the workflow terminal state — "
                                + "a premature COMPLETED here means the dispatch path is stamping "
                                + "terminal state at dispatch (regression vs. the documented contract)"),
                () -> assertEquals(f.workflowId, linkedWorkflowId,
                        "linked workflow_run.workflow_id must point at the schedule's target_id"));
    }

    @Test
    void manualTrigger_oneShotWorkflowSchedule_deactivatesScheduleAfterDispatch() {
        Fixture f = seedFixture("one-shot", /*oneShot=*/ true);

        // Sanity check: schedule starts active.
        assertEquals(true, jdbc.queryForObject(
                        "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, f.scheduleId),
                "fixture must start active");

        manualTriggerAs(f.orgId, f.scheduleId);

        // is_active is flipped INSIDE the @Transactional outer scope of triggerScheduleExecution,
        // so it is observable synchronously once manualTrigger returns — no Awaitility needed.
        Boolean isActiveAfter = jdbc.queryForObject(
                "SELECT is_active FROM schedules WHERE id = ?", Boolean.class, f.scheduleId);
        assertEquals(false, isActiveAfter,
                "one-shot WORKFLOW schedule must flip is_active=false at dispatch time — "
                        + "T037-3 atomicity contract. A still-active row here means the next "
                        + "poll tick will re-fire the schedule (exactly-once -> at-least-twice).");
    }

    @Test
    void manualTrigger_crossTenantSchedule_isSilentlyDroppedAndNoScheduleRunCreated() {
        Fixture orgB = seedFixture("cross-tenant-target", /*oneShot=*/ false);

        long scheduleRunsBefore = countScheduleRuns(orgB.scheduleId);

        // Caller is on a different org than the schedule's owner.
        manualTriggerAs("org-cross-tenant-A", orgB.scheduleId);

        // Give any (incorrectly-dispatched) VT a moment to insert before asserting.
        try {
            Thread.sleep(250);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        long scheduleRunsAfter = countScheduleRuns(orgB.scheduleId);
        assertEquals(scheduleRunsBefore, scheduleRunsAfter,
                "cross-tenant manualTrigger MUST be silently dropped — no schedule_run row "
                        + "may be created. A run count increase here means the "
                        + "findByIdAndOrgId guard in ScheduleExecutionPoller.manualTrigger "
                        + "regressed.");

        assertNull(workflowRunIdOnScheduleRun(orgB.scheduleId),
                "no schedule_run -> no workflow_run_id link");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private record Fixture(String orgId, String workflowId, String scheduleId) {}

    private Fixture seedFixture(String label, boolean oneShot) {
        String orgId = "org-sched-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-sched-" + label + "-" + UUID.randomUUID();
        String scheduleId = "sched-" + label + "-" + UUID.randomUUID();
        // workflow_runs.session_id -> agent_sessions(session_id) is a not-null FK
        // (changeset 016). WorkflowService.executeWorkflowAsync does NOT create the
        // agent_sessions row, and ScheduleExecutionPoller's WORKFLOW branch generates
        // a fresh UUID when the schedule has no resume_session_id — which would FK-violate.
        // Pre-seed a session and bind it via schedule.resume_session_id so the dispatch
        // VT can actually persist a workflow_runs row instead of falling into the
        // executeAndPersist catch block.
        String sessionId = "sess-sched-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'sched-user', ?, now(), now())
                """, sessionId, orgId);

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, workflowId, "Scheduled Workflow " + label, "", orgId);

        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression,
                                       target_type, target_id, resume_session_id,
                                       is_active, one_shot,
                                       contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '0 0 0 * * *', 'WORKFLOW', ?, ?, true, ?,
                        'manual-trigger probe', ?, now(), now())
                """, scheduleId, "Probe " + label, "", workflowId, sessionId, oneShot, orgId);

        return new Fixture(orgId, workflowId, scheduleId);
    }

    private void manualTriggerAs(String callerOrgId, String scheduleId) {
        // manualTrigger reads callerOrgId from AgentContextHolder; bind a ScopedValue so the
        // call sees the intended tenant. ApplicationReadyEvent and the cron-poll both run
        // in a system context (no AgentContextHolder binding), which is by design — the
        // tenant filter is for human-driven manual triggers only.
        ScopedValue
                .where(com.operativus.agentmanager.core.callback.AgentContextHolder.orgId, callerOrgId)
                .run(() -> poller.manualTrigger(scheduleId));
    }

    private String workflowRunIdOnScheduleRun(String scheduleId) {
        try {
            return jdbc.queryForObject(
                    "SELECT workflow_run_id FROM schedule_runs WHERE schedule_id = ?",
                    String.class, scheduleId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    private long countScheduleRuns(String scheduleId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Long.class, scheduleId);
    }
}
