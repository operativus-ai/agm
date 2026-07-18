package com.operativus.agentmanager.control.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskStatusEvent;

/**
 * Domain Responsibility: Contract for emitting A2A task lifecycle events to a connected
 * peer or stream subscriber.
 *
 * Gap 2.2 Implementation: Defines the "Task Status Event listener architecture" described
 * in the SDD. Implementations route {@link A2aTaskStatusEvent} records to the appropriate
 * transport: SSE sink, WebSocket session, or an outbound webhook to the originating peer.
 *
 * The emitter is task-scoped — one instance per active A2A inbound execution. The
 * {@code A2ATaskExecutor} creates an emitter at task submission time and passes it through
 * the execution lifecycle.
 *
 * Lifecycle signals ({@code submit}, {@code startWork}, {@code complete}, {@code cancel})
 * mirror the {@code io.a2a.spec} protocol event model, enabling peer systems to interrupt
 * and cancel long-running nested threads natively.
 *
 * Architecture: Interface only. No Spring context coupling. Implementations may be:
 *  - {@code SseAgentEmitter}  — pushes events to an SSE {@code SseEmitter}.
 *  - {@code WebhookAgentEmitter} — POSTs events to the peer's callback URL.
 *  - {@code NoOpAgentEmitter}  — discards events (for synchronous fire-and-forget calls).
 */
public interface AgentEmitter {

    /** Task received and accepted by the AGM execution layer. */
    void submit(String taskId);

    /** Execution is actively running on a virtual thread. */
    void startWork(String taskId, String runId);

    /** Execution completed successfully; final output is available. */
    void complete(String taskId, String runId, String output);

    /** Execution was cancelled by a peer cancel signal or internal policy. */
    void cancel(String taskId, String runId, String reason);

    /** HITL checkpoint triggered; execution is suspended pending human approval. */
    void pause(String taskId, String runId, String approvalId);

    /** Token budget ceiling breached; renegotiation required before proceeding. */
    void budgetHalt(String taskId, String runId);

    /** Execution failed with an error. */
    void error(String taskId, String runId, String errorDetail);

    /**
     * Emits a raw {@link A2aTaskStatusEvent} for callers that have already constructed the event.
     * Implementations should delegate to the appropriate typed method above, or serialize directly.
     */
    void emit(A2aTaskStatusEvent event);
}
