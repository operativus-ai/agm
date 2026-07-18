package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.service.ScheduleExecutionPoller;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Domain Responsibility: Pins
 *   {@link ScheduleExecutionPoller#syncWorkflowScheduleRuns} — the workflow-side back-propagation
 *   sweep that mirrors a workflow_run's terminal status onto the parent schedule_run row
 *   without crossing module boundaries (schedule_runs is the schedule-execution audit trail;
 *   workflow_runs is the workflow-execution audit trail).
 *
 *   <p>Sibling {@link WorkflowScheduledExecutionRuntimeTest} pins the dispatch side
 *   (schedule_run is created + linked to workflow_run, schedule_run.status stays RUNNING).
 *   This class pins the sync side — RUNNING schedule_runs get their linked workflow_run's
 *   terminal status mirrored on the next sync tick.
 *
 *   <p>Contract pinned here:
 *   <ul>
 *     <li>COMPLETED workflow_run → schedule_run flips to COMPLETED + completed_at stamped</li>
 *     <li>FAILED workflow_run → schedule_run flips to FAILED + completed_at stamped</li>
 *     <li>CANCELLED workflow_run → schedule_run flips to CANCELLED + completed_at stamped</li>
 *     <li>RUNNING workflow_run → schedule_run untouched (non-terminal, next tick will retry)</li>
 *     <li>PAUSED workflow_run → schedule_run untouched (HITL in-flight)</li>
 *     <li>schedule_runs with NULL workflow_run_id → skipped by the query filter
 *         {@code findByStatusAndWorkflowRunIdIsNotNull}; pinning the filter prevents agent-side
 *         schedule_runs (which never carry a workflow_run_id) from being miscategorized</li>
 *     <li>schedule_runs pointing at a missing workflow_run row → silently skipped
 *         (getWorkflowRunStatus returns empty)</li>
 *   </ul>
 *
 *   <p>The poller's @Scheduled cron is pushed to 24h in application-test.properties; each
 *   case calls {@link ScheduleExecutionPoller#syncWorkflowScheduleRuns} directly.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowScheduleRunSyncRuntimeTest extends BaseIntegrationTest {

    @Autowired private ScheduleExecutionPoller poller;

    @BeforeEach
    void seedFixtures() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // ─── W1.1 / W1.2 / W1.3 — terminal mirror matrix ─────────────────────────

    @Test
    void syncWorkflowScheduleRuns_completedWorkflowRun_mirrorsToScheduleRun() {
        Fixture f = seedLinkedPair("sync-completed", "COMPLETED");

        poller.syncWorkflowScheduleRuns();

        assertAll("COMPLETED workflow_run mirror sync",
                () -> assertEquals("COMPLETED", scheduleRunStatus(f.scheduleRunId),
                        "schedule_run.status must mirror the workflow_run's terminal COMPLETED — "
                                + "missing mirror leaves the schedule_run stuck in RUNNING and "
                                + "subsequent ticks keep re-polling the same finished workflow"),
                () -> assertNotNull(scheduleRunCompletedAt(f.scheduleRunId),
                        "completed_at MUST be stamped on terminal mirror — schedule history "
                                + "and SLA reporting depend on it"));
    }

    @Test
    void syncWorkflowScheduleRuns_failedWorkflowRun_mirrorsToScheduleRun() {
        Fixture f = seedLinkedPair("sync-failed", "FAILED");

        poller.syncWorkflowScheduleRuns();

        assertAll("FAILED workflow_run mirror sync",
                () -> assertEquals("FAILED", scheduleRunStatus(f.scheduleRunId),
                        "FAILED must propagate to schedule_run — DAG dependency gates "
                                + "(SchedulesDagDependencyRuntimeTest) read from schedule_runs, "
                                + "and a stuck RUNNING parent would silently block dependents forever"),
                () -> assertNotNull(scheduleRunCompletedAt(f.scheduleRunId),
                        "completed_at MUST be stamped on terminal mirror"));
    }

    @Test
    void syncWorkflowScheduleRuns_cancelledWorkflowRun_mirrorsToScheduleRun() {
        Fixture f = seedLinkedPair("sync-cancelled", "CANCELLED");

        poller.syncWorkflowScheduleRuns();

        assertAll("CANCELLED workflow_run mirror sync",
                () -> assertEquals("CANCELLED", scheduleRunStatus(f.scheduleRunId),
                        "CANCELLED workflow_run (whether user-cancel, stuck-PAUSED sweep, or "
                                + "orphan sweep) must propagate to the schedule_run audit trail"),
                () -> assertNotNull(scheduleRunCompletedAt(f.scheduleRunId),
                        "completed_at MUST be stamped on terminal mirror"));
    }

    // ─── W1.4 / W1.5 — non-terminal workflow_runs leave schedule_run alone ───

    @Test
    void syncWorkflowScheduleRuns_runningWorkflowRun_leavesScheduleRunUntouched() {
        Fixture f = seedLinkedPair("sync-running", "RUNNING");

        poller.syncWorkflowScheduleRuns();

        assertAll("RUNNING workflow_run is NOT mirrored",
                () -> assertEquals("RUNNING", scheduleRunStatus(f.scheduleRunId),
                        "RUNNING is not a terminal state — sync must leave schedule_run "
                                + "untouched and retry on the next tick. Premature mirror here "
                                + "would close out a schedule_run while its workflow is still "
                                + "executing."),
                () -> assertNull(scheduleRunCompletedAt(f.scheduleRunId),
                        "completed_at must remain null — workflow is still in-flight"));
    }

    @Test
    void syncWorkflowScheduleRuns_pausedWorkflowRun_leavesScheduleRunUntouched() {
        Fixture f = seedLinkedPair("sync-paused", "PAUSED");

        poller.syncWorkflowScheduleRuns();

        assertAll("PAUSED workflow_run is NOT mirrored",
                () -> assertEquals("RUNNING", scheduleRunStatus(f.scheduleRunId),
                        "PAUSED is the HITL hold state — sync must wait for the human approval "
                                + "to flip the workflow to a terminal state before touching the "
                                + "schedule_run. A mirror here would mark the schedule done while "
                                + "the workflow sits awaiting approval."),
                () -> assertNull(scheduleRunCompletedAt(f.scheduleRunId),
                        "completed_at must remain null while the workflow is PAUSED"));
    }

    // ─── W1.6 — agent-side schedule_runs are skipped by the query filter ────

    @Test
    void syncWorkflowScheduleRuns_agentSideScheduleRunWithNullWorkflowRunId_isSkipped() {
        // Seed a RUNNING schedule_run with workflow_run_id=NULL — the shape an
        // AGENT/TEAM-targeted schedule produces. Sync must not touch it.
        String scheduleRunId = seedAgentSideScheduleRun("sync-agent-side");

        poller.syncWorkflowScheduleRuns();

        assertAll("agent-side schedule_run untouched by workflow sync",
                () -> assertEquals("RUNNING", scheduleRunStatus(scheduleRunId),
                        "AGENT/TEAM schedule_runs (workflow_run_id IS NULL) MUST be skipped by "
                                + "the workflow-side sync — the query filter "
                                + "findByStatusAndWorkflowRunIdIsNotNull is the only thing keeping "
                                + "this clean. A change to read all RUNNING schedule_runs would "
                                + "see NULL workflow_run_id rows + Optional.empty workflow status "
                                + "→ no-op, but the filter is the right contract surface to pin"),
                () -> assertNull(scheduleRunCompletedAt(scheduleRunId),
                        "completed_at must remain null on agent-side rows"));
    }

    // ─── W1.7 — orphaned workflow_run_id (workflow_run row gone) silently skipped ──

    @Test
    void syncWorkflowScheduleRuns_orphanedWorkflowRunIdReference_isSilentlyNoOp() {
        // Schedule_run carries a workflow_run_id pointing at a row that no longer exists
        // (workflow_run was hard-deleted, or stamped before insert finalized, etc).
        // getWorkflowRunStatus returns empty → ifPresent skips → schedule_run untouched.
        String scheduleRunId = seedScheduleRunWithMissingWorkflowRunId("sync-orphan");

        poller.syncWorkflowScheduleRuns();

        assertEquals("RUNNING", scheduleRunStatus(scheduleRunId),
                "schedule_run pointing at a missing workflow_run row MUST be silently skipped — "
                        + "throwing here would block the sync loop and leave subsequent "
                        + "schedule_runs unmirrored. The ifPresent branch is the right contract.");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private record Fixture(String orgId, String scheduleId, String workflowId,
                           String workflowRunId, String scheduleRunId) {}

    /**
     * Seeds the full chain a RUNNING workflow-backed schedule_run needs to be eligible for
     * sync: org/session/workflow/schedule + workflow_run in the requested status + a
     * schedule_run linked via workflow_run_id with status='RUNNING'.
     *
     * <p>Status is interpolated into the workflow_runs INSERT because the column is a
     * text-backed enum; the helper is the only writer so SQL injection is not in scope.
     */
    private Fixture seedLinkedPair(String label, String workflowRunStatus) {
        if (!workflowRunStatus.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("status must be uppercase enum identifier; got: " + workflowRunStatus);
        }
        String orgId = "org-sync-" + label + "-" + UUID.randomUUID();
        String sessionId = "sess-sync-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-sync-" + label + "-" + UUID.randomUUID();
        String scheduleId = "sched-sync-" + label + "-" + UUID.randomUUID();
        String workflowRunId = "wfrun-sync-" + label + "-" + UUID.randomUUID();
        String scheduleRunId = "schedrun-sync-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'sync-user', ?, now(), now())
                """, sessionId, orgId);

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, 'sync-test', ?, now(), now())
                """, workflowId, "Sync Probe " + label, orgId);

        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type,
                                       target_id, resume_session_id, is_active, one_shot,
                                       contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, '', '0 0 0 * * *', 'WORKFLOW', ?, ?, true, false,
                        'sync probe', ?, now(), now())
                """, scheduleId, "Probe " + label, workflowId, sessionId, orgId);

        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload, org_id,
                                           created_at, updated_at)
                VALUES (?, ?, ?, '%s', 0, 'sync-payload', ?, now(), now())
                """.formatted(workflowRunStatus),
                workflowRunId, workflowId, sessionId, orgId);

        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                           output, error_message, workflow_run_id)
                VALUES (?, ?, 'RUNNING', ?, NULL, NULL, NULL, ?)
                """,
                scheduleRunId, scheduleId, LocalDateTime.now().minusMinutes(1), workflowRunId);

        return new Fixture(orgId, scheduleId, workflowId, workflowRunId, scheduleRunId);
    }

    /** Seeds a RUNNING schedule_run shaped like an AGENT/TEAM target (no workflow_run_id link). */
    private String seedAgentSideScheduleRun(String label) {
        String orgId = "org-sync-" + label + "-" + UUID.randomUUID();
        String scheduleId = "sched-sync-" + label + "-" + UUID.randomUUID();
        String scheduleRunId = "schedrun-sync-" + label + "-" + UUID.randomUUID();
        String agentId = "agent-sync-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, agentId, "Agent " + label);

        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type,
                                       target_id, resume_session_id, is_active, one_shot,
                                       contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, '', '0 0 0 * * *', 'AGENT', ?, NULL, true, false,
                        'agent sync probe', ?, now(), now())
                """, scheduleId, "Agent Probe " + label, agentId, orgId);

        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                           output, error_message, workflow_run_id)
                VALUES (?, ?, 'RUNNING', ?, NULL, NULL, NULL, NULL)
                """,
                scheduleRunId, scheduleId, LocalDateTime.now().minusMinutes(1));

        return scheduleRunId;
    }

    /**
     * Seeds a RUNNING schedule_run whose workflow_run_id references a row that does not
     * exist. Used to pin the ifPresent guard at the call to getWorkflowRunStatus.
     */
    private String seedScheduleRunWithMissingWorkflowRunId(String label) {
        String orgId = "org-sync-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-sync-" + label + "-" + UUID.randomUUID();
        String scheduleId = "sched-sync-" + label + "-" + UUID.randomUUID();
        String scheduleRunId = "schedrun-sync-" + label + "-" + UUID.randomUUID();
        String missingWorkflowRunId = "missing-wfrun-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, '', ?, now(), now())
                """, workflowId, "Orphan Probe " + label, orgId);

        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression, target_type,
                                       target_id, resume_session_id, is_active, one_shot,
                                       contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, '', '0 0 0 * * *', 'WORKFLOW', ?, NULL, true, false,
                        'orphan probe', ?, now(), now())
                """, scheduleId, "Orphan Probe " + label, workflowId, orgId);

        jdbc.update("""
                INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at,
                                           output, error_message, workflow_run_id)
                VALUES (?, ?, 'RUNNING', ?, NULL, NULL, NULL, ?)
                """,
                scheduleRunId, scheduleId, LocalDateTime.now().minusMinutes(1),
                missingWorkflowRunId);

        return scheduleRunId;
    }

    private String scheduleRunStatus(String scheduleRunId) {
        return jdbc.queryForObject(
                "SELECT status FROM schedule_runs WHERE id = ?", String.class, scheduleRunId);
    }

    private java.sql.Timestamp scheduleRunCompletedAt(String scheduleRunId) {
        return jdbc.queryForObject(
                "SELECT completed_at FROM schedule_runs WHERE id = ?",
                java.sql.Timestamp.class, scheduleRunId);
    }
}
