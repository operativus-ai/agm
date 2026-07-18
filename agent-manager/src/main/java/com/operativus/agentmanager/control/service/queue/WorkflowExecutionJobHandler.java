package com.operativus.agentmanager.control.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.control.repository.WorkflowRunRepository;
import com.operativus.agentmanager.control.service.WorkflowService;
import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.core.entity.WorkflowRun;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class WorkflowExecutionJobHandler implements JobHandler {

    public static final String JOB_TYPE = "WORKFLOW_EXECUTION";

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionJobHandler.class);

    /** Terminal statuses for the dispatch path. PAUSED / AWAITING_ROUTE_SELECTION /
     *  AWAITING_HUMAN_REVIEW are also "dispatch reached a stable resting point" — the
     *  resume/decide path creates a new job (or VT) to drive the next step, so the original
     *  dispatcher's responsibility ends. AWAITING_HUMAN_REVIEW was missing from this set,
     *  so any run pausing for review (REQ-HR-3) left its job polling the full 15-minute
     *  deadline instead of completing. */
    private static final Set<RunStatus> TERMINAL = EnumSet.of(
            RunStatus.COMPLETED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.PAUSED,
            RunStatus.AWAITING_ROUTE_SELECTION,
            RunStatus.AWAITING_HUMAN_REVIEW);

    private static final long POLL_INTERVAL_MS = 500L;
    private static final long DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(15);

    private final WorkflowService workflowService;
    private final WorkflowRunRepository workflowRunRepository;
    private final ObjectMapper objectMapper;

    public WorkflowExecutionJobHandler(WorkflowService workflowService,
                                       WorkflowRunRepository workflowRunRepository,
                                       ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.workflowRunRepository = workflowRunRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job) throws Exception {
        Payload payload = objectMapper.readValue(job.getPayload(), Payload.class);
        log.info("Starting workflow execution: workflowId={} sessionId={}", payload.workflowId(), payload.sessionId());
        String runId = workflowService.executeWorkflowAsync(payload.workflowId(), payload.input(), payload.sessionId());
        log.info("Workflow execution dispatched: workflowId={} runId={}", payload.workflowId(), runId);

        // Block the queue thread until the workflow_run reaches a terminal state. Without
        // this, executeWorkflowAsync spawns a virtual thread and returns instantly — the
        // BackgroundJob is marked COMPLETED by PersistentJobQueueService even when the
        // workflow_run later transitions to FAILED. Polling the row keeps the handler's
        // own thread (already a per-job virtual thread, blocking is cheap) alive until the
        // outcome is known, so the queue's status reflects the workflow's status.
        RunStatus terminalStatus = awaitTerminal(runId);
        if (terminalStatus == RunStatus.FAILED) {
            // Surface the failure so PersistentJobQueueService.executeJob records it and
            // moves the job to the DLQ. This is NON-retryable: a FAILED run is a recorded,
            // deterministic outcome — retrying would re-invoke executeWorkflowAsync, mint a
            // SECOND workflow_runs row, and re-run every agent step. The workflow_runs row
            // already carries detailed state for forensics. See NonRetryableJobException.
            throw new NonRetryableJobException("Workflow run " + runId + " ended in FAILED status");
        }
    }

    private RunStatus awaitTerminal(String runId) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE_NANOS;
        while (System.nanoTime() < deadline) {
            WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
            if (run != null && TERMINAL.contains(run.getStatus())) {
                return run.getStatus();
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Workflow run " + runId
                + " did not reach a terminal status within " + (DEADLINE_NANOS / 1_000_000_000) + "s");
    }

    public record Payload(String workflowId, String input, String sessionId) {}
}
