package ai.operativus.agentmanager.integration.workflows;

import ai.operativus.agentmanager.control.service.WorkflowService;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Domain Responsibility: Pins {@link WorkflowService#resumeWorkflowRun} state-mismatch
 *   matrix. The service is invoked by {@link ai.operativus.agentmanager.control.service.queue.WorkflowResumeJobHandler}
 *   on the back of a {@code WORKFLOW_RESUME} background job; the controller enqueues without
 *   pre-validating run state (see {@link ai.operativus.agentmanager.control.controller.WorkflowsController#resumeWorkflowRun}
 *   for the documented "deferred validation" contract).
 *
 *   <p>The full state-handling matrix at the service layer is:
 *   <ul>
 *     <li>PAUSED  → resumes execution (happy path; covered by {@link WorkflowsRuntimeTest})</li>
 *     <li>RUNNING / COMPLETED / FAILED / CANCELLED → silently logs and returns (no state change)</li>
 *     <li>Unknown runId → throws IllegalArgumentException (handler propagates; job marked FAILED)</li>
 *   </ul>
 *
 *   <p>Why this matters: the silent no-op for non-PAUSED states means a user-issued resume
 *   request lands as a successfully-completed job that did nothing. The controller returns
 *   202 + a jobId, and the job's status will reflect success — but the workflow does not
 *   advance. Pinning this prevents silently flipping the contract to throw (which would
 *   bubble up as job FAILED + visible error) without explicit intent.
 *
 *   <p>Unknown-runId behavior contrasts: the service throws IAE, so the handler marks the
 *   job FAILED. That asymmetry (unknown→loud, wrong-state→silent) is the contract under
 *   test here.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class WorkflowResumeStateMismatchRuntimeTest extends BaseIntegrationTest {

    @Autowired private WorkflowService workflowService;

    @BeforeEach
    void seedModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    // ─── R1 — wrong-state matrix: service silently no-ops, NO state change ───

    @Test
    void resume_runningRun_isSilentlyNoOp_runStaysRunning() {
        String runId = seedWorkflowRun("resume-running", "RUNNING", 3, "running-payload");

        // Direct service call mirrors what the handler would do; bypasses queue surface
        // to isolate the state-mismatch branch.
        workflowService.resumeWorkflowRun(runId, "user-approved-output");

        assertAll("resume on RUNNING is silent no-op",
                () -> assertEquals("RUNNING", statusOf(runId),
                        "RUNNING workflow_run must not be touched — a resume on an actively-"
                                + "executing run would race with the in-flight VT"),
                () -> assertEquals(3, currentStepOrderOf(runId),
                        "current_step_order must not advance — resume only progresses PAUSED rows"),
                () -> assertEquals("running-payload", currentPayloadOf(runId),
                        "current_payload must not be overwritten with the resume's output"));
    }

    @Test
    void resume_completedRun_isSilentlyNoOp_terminalPreserved() {
        String runId = seedWorkflowRun("resume-completed", "COMPLETED", 5, "final-output");

        workflowService.resumeWorkflowRun(runId, "late-approved-output");

        assertAll("resume on COMPLETED is silent no-op",
                () -> assertEquals("COMPLETED", statusOf(runId),
                        "terminal COMPLETED must NOT be re-opened by resume; resurrection "
                                + "would invalidate downstream consumers that have already "
                                + "read the final output"),
                () -> assertEquals("final-output", currentPayloadOf(runId),
                        "final payload must remain intact — the resume's output must NOT "
                                + "overwrite a terminal row's recorded result"));
    }

    @Test
    void resume_failedRun_isSilentlyNoOp_terminalPreserved() {
        String runId = seedWorkflowRun("resume-failed", "FAILED", 2, "error-payload");

        workflowService.resumeWorkflowRun(runId, "retry-output");

        assertAll("resume on FAILED is silent no-op",
                () -> assertEquals("FAILED", statusOf(runId),
                        "FAILED must NOT auto-transition to RUNNING via resume — retry "
                                + "semantics belong to a separate retry path, not to resume"),
                () -> assertEquals("error-payload", currentPayloadOf(runId),
                        "FAILED payload (typically carrying the error message) must be preserved"));
    }

    @Test
    void resume_cancelledRun_isSilentlyNoOp_terminalPreserved() {
        String runId = seedWorkflowRun("resume-cancelled", "CANCELLED", 1, "cancelled-payload");

        workflowService.resumeWorkflowRun(runId, "post-cancel-output");

        assertAll("resume on CANCELLED is silent no-op",
                () -> assertEquals("CANCELLED", statusOf(runId),
                        "CANCELLED must NOT be re-opened — the stuck-PAUSED sweep, orphan "
                                + "sweep, REST cancel, and HITL-reject paths all produce CANCELLED; "
                                + "a resume that resurrected them would silently undo the cancellation"),
                () -> assertEquals("cancelled-payload", currentPayloadOf(runId),
                        "cancelled payload (carrying the cancellation reason) must be preserved"));
    }

    // ─── R2 — unknown runId: service throws IAE (loud failure, NOT no-op) ───

    @Test
    void resume_unknownRunId_throwsIllegalArgumentException() {
        String unknownRunId = "missing-run-" + UUID.randomUUID();

        // Contract asymmetry pin: unknown runId is loud (IAE → handler propagates →
        // background_jobs row marked FAILED with the message). Wrong-state is silent
        // (the four preceding tests). A change that made unknown-runId also silent
        // would mask a class of "I cancelled the wrong run" / "I'm looking at stale
        // UI" bugs that currently surface as FAILED jobs.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> workflowService.resumeWorkflowRun(unknownRunId, "irrelevant-output"),
                "unknown runId MUST throw — the silent-no-op contract only applies to "
                        + "state-mismatch on a found row");

        assertNotNull(ex.getMessage(), "IAE must carry a diagnostic message");
        assertEquals(true, ex.getMessage().contains(unknownRunId),
                "IAE message MUST include the runId for triage; got: " + ex.getMessage());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Seeds the agent_sessions → workflows → workflow_runs chain needed for resume tests.
     * Status is interpolated into SQL (uppercase enum identifier, validated) — bind params
     * would need an explicit ::run_status cast against the text-backed enum column.
     */
    private String seedWorkflowRun(String label, String status, int stepOrder, String payload) {
        if (!status.matches("[A-Z_]+")) {
            throw new IllegalArgumentException("status must be uppercase enum identifier; got: " + status);
        }
        String orgId = "org-" + label + "-" + UUID.randomUUID();
        String sessionId = "sess-" + label + "-" + UUID.randomUUID();
        String workflowId = "wf-" + label + "-" + UUID.randomUUID();
        String runId = "wfrun-" + label + "-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, created_at, updated_at)
                VALUES (?, 'resume-user', ?, now(), now())
                """, sessionId, orgId);

        jdbc.update("""
                INSERT INTO workflows (id, name, description, org_id, created_at, updated_at)
                VALUES (?, ?, 'resume-state-test', ?, now(), now())
                """, workflowId, "Resume Probe " + label, orgId);

        jdbc.update("""
                INSERT INTO workflow_runs (id, workflow_id, session_id, status,
                                           current_step_order, current_payload, org_id,
                                           created_at, updated_at)
                VALUES (?, ?, ?, '%s', ?, ?, ?, now(), now())
                """.formatted(status),
                runId, workflowId, sessionId, stepOrder, payload, orgId);

        return runId;
    }

    private String statusOf(String runId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
    }

    private Integer currentStepOrderOf(String runId) {
        return jdbc.queryForObject(
                "SELECT current_step_order FROM workflow_runs WHERE id = ?", Integer.class, runId);
    }

    private String currentPayloadOf(String runId) {
        return jdbc.queryForObject(
                "SELECT current_payload FROM workflow_runs WHERE id = ?", String.class, runId);
    }
}
