package com.operativus.agentmanager.core.event;

/**
 * Domain Responsibility: Enumerates the discrete event types that flow through {@code AgentRunEventBus}
 *     and are persisted to the {@code agent_run_events} table. Each value is a stable audit-log
 *     discriminator — DO NOT rename or remove existing entries without a data-migration plan.
 * State: Stateless (Enum).
 */
public enum AgentRunEventType {
    RUN_START,
    RUN_COMPLETE,
    RUN_FAILED,
    RUN_PAUSED,
    RUN_CANCELLED,
    BUDGET_EXCEEDED,
    TOOL_INVOKED,
    TOOL_COMPLETED,
    DELEGATION_START,
    DELEGATION_COMPLETE,
    /** Emitted by {@code HandOffTool} when a swarm agent voluntarily transfers control to another
     *  agent (after DAG + tier-escalation validation passes, before the {@code SwarmHandOffException}
     *  redirects the control loop). Unlike DELEGATION_* there is no COMPLETE pairing — the target's
     *  execution surfaces as its own RUN_START. Payload keys: sourceAgentId, sourceAgentName,
     *  targetAgentId, targetAgentName, contextLength. */
    HANDOFF,
    ORCHESTRATOR_DECISION,
    LLM_REQUEST,
    LLM_RESPONSE,
    /** REQ-TT-4 — emitted when the TASKS coordinator creates a task via TaskManagementTool.
     *  Payload keys: taskId, title, assigneeAgentId, dependencies. */
    TASK_CREATED,
    /** REQ-TT-4 — emitted on every task lifecycle transition (PENDING→IN_PROGRESS via
     *  worker-loop dispatch; IN_PROGRESS→COMPLETED/FAILED on assignee terminal; PENDING→
     *  BLOCKED on failed dep; and explicit updates via TaskManagementTool.updateTaskStatus).
     *  Payload keys: taskId, status, result, dispatchedAt (when relevant). */
    TASK_UPDATED;

    /**
     * @summary Whether this event is in the <b>always-emitted lifecycle tier</b> — emitted even when
     *     granular run-event streaming is disabled (see {@code AgentRunEventBus} and
     *     {@code agm.events.granular-streaming.enabled}).
     * @logic The lifecycle/terminal tier is low-volume (≈ one per run plus its terminal) and is
     *     load-bearing for run status, audit, HITL, and budget enforcement — so it is never gated.
     *     Everything else (per-LLM-call, per-tool-call, delegation/handoff, orchestrator/task traces)
     *     is the high-volume "granular" tier that the flag suppresses to cut event-streaming overhead.
     */
    public boolean isAlwaysEmitted() {
        return switch (this) {
            case RUN_START, RUN_COMPLETE, RUN_FAILED, RUN_PAUSED, RUN_CANCELLED, BUDGET_EXCEEDED -> true;
            default -> false;
        };
    }
}
