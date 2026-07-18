package ai.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.control.repository.WorkflowRunRepository;
import ai.operativus.agentmanager.control.service.WorkflowService;
import ai.operativus.agentmanager.core.entity.BackgroundJob;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Pin the poll-until-terminal contract on
 * {@link WorkflowExecutionJobHandler} added by PR #930 (Bug #17b).
 *
 * Pre-fix the handler called {@code workflowService.executeWorkflowAsync} and
 * returned immediately — the spawned virtual thread continued running, and
 * {@code PersistentJobQueueService.executeJob} marked the {@code background_job}
 * row COMPLETED even when the {@code workflow_run} subsequently transitioned to
 * FAILED. The queue's status was therefore a lie for any caller observing job
 * outcome.
 *
 * Post-fix the handler polls {@code workflow_runs} until the status reaches a
 * terminal value (COMPLETED, FAILED, CANCELLED, PAUSED, AWAITING_ROUTE_SELECTION).
 * On FAILED the handler throws so {@code PersistentJobQueueService.executeJob}
 * sees the failure, increments {@code retryCount}, and (after exhausting retries)
 * lands the row in the DLQ. The other terminal statuses are "dispatcher's
 * responsibility ends" — return normally.
 *
 * State: Stateless.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionJobHandlerTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    private WorkflowExecutionJobHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new WorkflowExecutionJobHandler(workflowService, workflowRunRepository, objectMapper);
    }

    @Test
    void jobType_isWorkflowExecution() {
        assertThat(handler.jobType()).isEqualTo("WORKFLOW_EXECUTION");
        assertThat(handler.jobType()).isEqualTo(WorkflowExecutionJobHandler.JOB_TYPE);
    }

    @Test
    void execute_workflowRunsToFailed_throwsSoQueueLogsTheFailure() throws Exception {
        // Bug #17b regression guard. Pre-fix this same scenario would mark the
        // background_job row COMPLETED because the handler returned immediately
        // after executeWorkflowAsync spawned its VT. Post-fix the handler polls,
        // sees FAILED, and throws so the queue records the failure for DLQ.
        String runId = "run-failed-fixture";
        when(workflowService.executeWorkflowAsync(anyString(), anyString(), anyString()))
                .thenReturn(runId);
        when(workflowRunRepository.findById(runId))
                .thenReturn(Optional.of(runWithStatus(runId, RunStatus.FAILED)));

        BackgroundJob job = jobWithPayload("wf-abc", "test input", "session-1");

        // Non-retryable: a FAILED run is a terminal business outcome. Throwing the marker
        // makes PersistentJobQueueService DLQ the job WITHOUT re-running the workflow (which
        // would mint a duplicate workflow_runs row). See NonRetryableJobException.
        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(NonRetryableJobException.class)
                .hasMessageContaining(runId)
                .hasMessageContaining("FAILED");
    }

    @Test
    void execute_workflowRunsToCompleted_returnsNormally() throws Exception {
        // Counterpart to the FAILED case: COMPLETED is a normal terminal —
        // handler returns without throwing so PersistentJobQueueService marks
        // background_job COMPLETED. This pins the happy path against future
        // over-eager throw-on-any-terminal regressions.
        String runId = "run-completed-fixture";
        when(workflowService.executeWorkflowAsync(anyString(), anyString(), anyString()))
                .thenReturn(runId);
        when(workflowRunRepository.findById(runId))
                .thenReturn(Optional.of(runWithStatus(runId, RunStatus.COMPLETED)));

        BackgroundJob job = jobWithPayload("wf-abc", "test input", "session-1");

        assertThatCode(() -> handler.execute(job)).doesNotThrowAnyException();
    }

    @Test
    void execute_workflowReachesPaused_returnsNormally_dispatcherResponsibilityEnds() throws Exception {
        // PAUSED is a "stable resting point" terminal per the TERMINAL EnumSet
        // javadoc — the resume endpoint creates a new job to drive the next step,
        // so the original dispatcher's responsibility ends. Pinned here so a
        // future TERMINAL set narrowing (e.g. dropping PAUSED) surfaces visibly.
        String runId = "run-paused-fixture";
        when(workflowService.executeWorkflowAsync(anyString(), anyString(), anyString()))
                .thenReturn(runId);
        when(workflowRunRepository.findById(runId))
                .thenReturn(Optional.of(runWithStatus(runId, RunStatus.PAUSED)));

        BackgroundJob job = jobWithPayload("wf-abc", "test input", "session-1");

        assertThatCode(() -> handler.execute(job)).doesNotThrowAnyException();
    }

    @Test
    void execute_workflowReachesAwaitingRouteSelection_returnsNormally() throws Exception {
        String runId = "run-awaiting-route-fixture";
        when(workflowService.executeWorkflowAsync(anyString(), anyString(), anyString()))
                .thenReturn(runId);
        when(workflowRunRepository.findById(runId))
                .thenReturn(Optional.of(runWithStatus(runId, RunStatus.AWAITING_ROUTE_SELECTION)));

        BackgroundJob job = jobWithPayload("wf-abc", "test input", "session-1");

        assertThatCode(() -> handler.execute(job)).doesNotThrowAnyException();
    }

    @Test
    void execute_workflowReachesCancelled_returnsNormally() throws Exception {
        String runId = "run-cancelled-fixture";
        when(workflowService.executeWorkflowAsync(anyString(), anyString(), anyString()))
                .thenReturn(runId);
        when(workflowRunRepository.findById(runId))
                .thenReturn(Optional.of(runWithStatus(runId, RunStatus.CANCELLED)));

        BackgroundJob job = jobWithPayload("wf-abc", "test input", "session-1");

        assertThatCode(() -> handler.execute(job)).doesNotThrowAnyException();
    }

    private WorkflowRun runWithStatus(String runId, RunStatus status) {
        WorkflowRun run = new WorkflowRun(runId, "wf-abc", "session-1", status, 0, null, "test-org");
        return run;
    }

    private BackgroundJob jobWithPayload(String workflowId, String input, String sessionId) throws Exception {
        BackgroundJob job = new BackgroundJob();
        WorkflowExecutionJobHandler.Payload payload =
                new WorkflowExecutionJobHandler.Payload(workflowId, input, sessionId);
        job.setPayload(objectMapper.writeValueAsString(payload));
        return job;
    }
}
