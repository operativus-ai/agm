package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.service.ScheduleExecutionPoller;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Locks the contract for WORKFLOW schedules created with
 *   {@code resume_session_id = null}. {@link ScheduleExecutionPoller}'s WORKFLOW branch
 *   now auto-creates an {@code agent_sessions} row owned by the schedule's tenant
 *   before invoking {@link ai.operativus.agentmanager.control.service.WorkflowService#executeWorkflowAsync},
 *   so the downstream {@code workflow_runs} INSERT no longer FK-violates on the
 *   {@code fk_workflow_runs_session} constraint.
 *
 *   <p>Contract:
 *   <ul>
 *     <li>The schedule_run reaches a non-FAILED state (RUNNING or terminal-by-sync) —
 *         no FK violation aborts dispatch.</li>
 *     <li>A {@code workflow_runs} row is created and linked via
 *         {@code schedule_runs.workflow_run_id}.</li>
 *     <li>The auto-created session belongs to the schedule's org (tenant isolation
 *         held).</li>
 *     <li>The session is attributed to the synthetic
 *         {@code ScheduleExecutionPoller.SCHEDULER_SYNTHETIC_USER_ID} user so
 *         observability can distinguish scheduler-spawned sessions.</li>
 *   </ul>
 *
 *   <p>Companion PR #550 ({@code WorkflowScheduledExecutionRuntimeTest}) covers the
 *   path where the schedule's {@code resume_session_id} is pre-seeded.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowScheduleNullSessionFailureRuntimeTest extends BaseIntegrationTest {

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
    void manualTrigger_workflowScheduleWithNullResumeSessionId_autoCreatesSession_dispatchesWorkflowRun() {
        String orgId = "org-null-sess-" + UUID.randomUUID();
        String workflowId = "wf-null-sess-" + UUID.randomUUID();
        String scheduleId = "sched-null-sess-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """, workflowId, "Null-session probe", "", orgId);

        // resume_session_id is intentionally NULL — the poller must auto-create the
        // session before invoking executeWorkflowAsync so the workflow_runs FK holds.
        jdbc.update("""
                INSERT INTO schedules (id, name, description, cron_expression,
                                       target_type, target_id, resume_session_id,
                                       is_active, one_shot,
                                       contextual_prompt, org_id, created_at, updated_at)
                VALUES (?, ?, ?, '0 0 0 * * *', 'WORKFLOW', ?, NULL, true, false,
                        'null-session probe', ?, now(), now())
                """, scheduleId, "Probe null-session", "", workflowId, orgId);

        ScopedValue
                .where(ai.operativus.agentmanager.core.callback.AgentContextHolder.orgId, orgId)
                .run(() -> poller.manualTrigger(scheduleId));

        // Wait for the dispatch VT to land a workflow_run row — that's the success
        // signal that the auto-created session held the FK.
        Awaitility.await("workflow_runs row linked to schedule_run for null-session schedule")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM workflow_runs WHERE workflow_id = ?",
                        Long.class, workflowId) == 1L);

        String status = jdbc.queryForObject(
                "SELECT status FROM schedule_runs WHERE schedule_id = ?",
                String.class, scheduleId);
        String workflowRunId = jdbc.queryForObject(
                "SELECT workflow_run_id FROM schedule_runs WHERE schedule_id = ?",
                String.class, scheduleId);
        String workflowRunSessionId = jdbc.queryForObject(
                "SELECT session_id FROM workflow_runs WHERE workflow_id = ?",
                String.class, workflowId);
        String sessionOrgId = jdbc.queryForObject(
                "SELECT org_id FROM agent_sessions WHERE session_id = ?",
                String.class, workflowRunSessionId);
        String sessionUserId = jdbc.queryForObject(
                "SELECT user_id FROM agent_sessions WHERE session_id = ?",
                String.class, workflowRunSessionId);

        assertAll("null-resume-session-id auto-create success path",
                () -> assertNotEquals("FAILED", status,
                        "WORKFLOW schedule with null resume_session_id must NOT land in FAILED — "
                                + "the auto-created session lets workflow_runs INSERT succeed; got "
                                + status),
                () -> assertNotNull(workflowRunId,
                        "schedule_run must carry a workflow_run_id linkage to the dispatched run"),
                () -> assertNotNull(workflowRunSessionId,
                        "workflow_run.session_id must be set — points at the auto-created session"),
                () -> assertEquals(orgId, sessionOrgId,
                        "auto-created session must belong to the schedule's org (tenant isolation); "
                                + "got '" + sessionOrgId + "'"),
                () -> assertEquals(ScheduleExecutionPoller.SCHEDULER_SYNTHETIC_USER_ID, sessionUserId,
                        "auto-created session must be attributed to the synthetic scheduler user "
                                + "so observability can distinguish scheduler-spawned sessions; got '"
                                + sessionUserId + "'"));
    }
}
