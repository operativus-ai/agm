package ai.operativus.agentmanager.control.a2a.model;

import java.time.Instant;

/**
 * Domain Responsibility: Immutable event record emitted at each lifecycle transition of an
 * inbound A2A task execution. Carried over SSE streams and JSON-RPC callbacks to the
 * originating peer.
 *
 * Gap 2.2 Implementation: Represents the "Task Status Event listener architecture"
 * required by the SDD. Peer systems consume these events to track progress, detect HITL
 * pauses, and respond to {@link A2aTaskStatus#BUDGET_HALT} signals by triggering a
 * renegotiation webhook rather than allowing runaway token consumption.
 *
 * @param taskId       Matches the {@code taskId} in the originating {@link A2aTaskRequest}.
 * @param status       Current lifecycle state of the task.
 * @param runId        AGM's internal run ID (present after WORKING state begins).
 * @param message      Human-readable status message or partial output (may be null).
 * @param errorDetail  Error description if status is {@link A2aTaskStatus#FAILED} (may be null).
 * @param timestamp    Wall-clock time this event was emitted.
 */
public record A2aTaskStatusEvent(
    String taskId,
    A2aTaskStatus status,
    String runId,
    String message,
    String errorDetail,
    Instant timestamp
) {
    public static A2aTaskStatusEvent of(String taskId, A2aTaskStatus status, String runId, String message) {
        return new A2aTaskStatusEvent(taskId, status, runId, message, null, Instant.now());
    }

    public static A2aTaskStatusEvent failed(String taskId, String runId, String errorDetail) {
        return new A2aTaskStatusEvent(taskId, A2aTaskStatus.FAILED, runId, null, errorDetail, Instant.now());
    }

    public static A2aTaskStatusEvent budgetHalt(String taskId, String runId) {
        return new A2aTaskStatusEvent(taskId, A2aTaskStatus.BUDGET_HALT, runId,
            "Token budget ceiling reached. Renegotiation required before execution can resume.", null, Instant.now());
    }
}
