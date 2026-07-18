package ai.operativus.agentmanager.control.a2a;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * Domain Responsibility: SSE-backed {@link AgentEmitter} that streams A2A task lifecycle
 * events to the connected HTTP client via a Spring {@link SseEmitter}.
 *
 * Gap 2.2 Implementation: Bridges the {@code AgentEmitter} interface to AGM's existing
 * SSE transport layer, allowing external A2A peers that initiate tasks via
 * {@code POST /api/v1/a2a/tasks} to receive real-time lifecycle events on the same
 * long-poll SSE response.
 *
 * Error handling: if the SSE sink is closed or an I/O error occurs, the emitter logs and
 * silently swallows — the caller's task continues regardless of observer connectivity.
 *
 * State: Stateful (holds a reference to a specific {@link SseEmitter} instance per task).
 */
public class SseAgentEmitter implements AgentEmitter {

    private static final Logger log = LoggerFactory.getLogger(SseAgentEmitter.class);

    private final SseEmitter sseEmitter;

    public SseAgentEmitter(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    @Override
    public void submit(String taskId) {
        send(A2aTaskStatusEvent.of(taskId, A2aTaskStatus.SUBMITTED, null, "Task accepted by AGM execution layer."));
    }

    @Override
    public void startWork(String taskId, String runId) {
        send(A2aTaskStatusEvent.of(taskId, A2aTaskStatus.WORKING, runId, "Execution started."));
    }

    @Override
    public void complete(String taskId, String runId, String output) {
        send(A2aTaskStatusEvent.of(taskId, A2aTaskStatus.COMPLETED, runId, output));
        sseEmitter.complete();
    }

    @Override
    public void cancel(String taskId, String runId, String reason) {
        send(A2aTaskStatusEvent.of(taskId, A2aTaskStatus.CANCELLED, runId, reason));
        sseEmitter.complete();
    }

    @Override
    public void pause(String taskId, String runId, String approvalId) {
        send(A2aTaskStatusEvent.of(taskId, A2aTaskStatus.PAUSED, runId,
            "HITL checkpoint triggered. Approval ID: " + approvalId));
    }

    @Override
    public void budgetHalt(String taskId, String runId) {
        send(A2aTaskStatusEvent.budgetHalt(taskId, runId));
    }

    @Override
    public void error(String taskId, String runId, String errorDetail) {
        send(A2aTaskStatusEvent.failed(taskId, runId, errorDetail));
        sseEmitter.completeWithError(new RuntimeException(errorDetail));
    }

    @Override
    public void emit(A2aTaskStatusEvent event) {
        send(event);
        if (event.status() == A2aTaskStatus.COMPLETED
                || event.status() == A2aTaskStatus.FAILED
                || event.status() == A2aTaskStatus.CANCELLED) {
            sseEmitter.complete();
        }
    }

    private void send(A2aTaskStatusEvent event) {
        try {
            sseEmitter.send(SseEmitter.event()
                .name("a2a-task-status")
                .data(event));
        } catch (IOException e) {
            log.debug("SseAgentEmitter: SSE sink closed for taskId={}. Event dropped: {}",
                event.taskId(), event.status());
        }
    }
}
