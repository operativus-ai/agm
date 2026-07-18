package ai.operativus.agentmanager.control.service.queue;

/**
 * Domain Responsibility: Signals that a {@link JobHandler#execute} failure is a
 *   terminal business outcome, NOT a transient error worth retrying. When a handler
 *   throws this, {@code PersistentJobQueueService} skips both retry layers (the
 *   in-line {@code RetryTemplate} and the outer re-queue) and lands the job in the
 *   DLQ immediately.
 * State: Stateless (Exception carrier).
 *
 * <p>The motivating case is workflow execution: a {@code workflow_run} reaching
 * FAILED is a recorded, deterministic outcome. Retrying re-invokes
 * {@code WorkflowService.executeWorkflowAsync}, which mints a fresh
 * {@code workflow_runs} row and re-runs every agent step — duplicating the run and
 * its side effects on each attempt. Throwing this marker preserves the
 * "job records the failure" contract (PR #930 / Bug #17b) while preventing the
 * re-execution.
 */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }
}
