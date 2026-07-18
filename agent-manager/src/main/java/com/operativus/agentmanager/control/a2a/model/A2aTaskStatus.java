package com.operativus.agentmanager.control.a2a.model;

/**
 * Domain Responsibility: Enumeration of A2A task lifecycle states aligned with the
 * standard A2A protocol event model ({@code io.a2a.spec}).
 *
 * Gap 2.2 Implementation: AGM previously had no inbound task lifecycle model for
 * externally-originated A2A executions. These states parallel the {@code AgentEmitter}
 * signals and are embedded in every {@code A2aTaskStatusEvent} emitted during execution.
 *
 * <ul>
 *   <li>{@link #SUBMITTED}   — Task received and queued, no work started yet.</li>
 *   <li>{@link #WORKING}     — Execution in progress on a virtual thread.</li>
 *   <li>{@link #PAUSED}      — HITL checkpoint triggered; awaiting human approval.</li>
 *   <li>{@link #COMPLETED}   — Execution finished successfully.</li>
 *   <li>{@link #FAILED}      — Execution terminated with an error.</li>
 *   <li>{@link #CANCELLED}   — Caller or system cancelled before completion.</li>
 *   <li>{@link #BUDGET_HALT} — Execution halted because the A2A FinOps token ceiling was breached;
 *                              a renegotiation signal must be sent before resuming.</li>
 * </ul>
 */
public enum A2aTaskStatus {
    SUBMITTED,
    WORKING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    BUDGET_HALT
}
