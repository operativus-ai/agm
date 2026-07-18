package com.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: The kind discriminator for a node in the DAG workflow engine
 *     (REQ-DR-5). Where {@link StepActionType} described the flat-list dispatcher's
 *     per-step behaviour, {@code NodeKind} is the typed-graph equivalent consumed by the
 *     {@code WorkflowNodeExecutor} SPI and the frontier scheduler (DAG plan §2.1/§2.4).
 *
 *     <ul>
 *       <li>{@link #AGENT} — runs an agent / team referenced by {@code agentId}.</li>
 *       <li>{@link #FUNCTION} — dispatches to a registered {@code WorkflowFunctionStep}
 *           bean (the AGM analog of Agno {@code executor=fn}).</li>
 *       <li>{@link #WORKFLOW} — nested sub-workflow ({@code config.workflowId}, depth-guarded).</li>
 *       <li>{@link #CONDITION} — selects {@code true}/{@code false} outgoing ports.</li>
 *       <li>{@link #ROUTER} — selects an outgoing port by choice key (reuses {@code RouteSelector}).</li>
 *       <li>{@link #LOOP} — frames a back-edge to a body subgraph with an iteration bound.</li>
 *       <li>{@link #JOIN} — fan-in barrier; waits for all predecessors before activating.</li>
 *       <li>{@link #PARALLEL} — fan-out gate (no-op node with N outgoing edges).</li>
 *       <li>{@link #WEBHOOK} — delegated to a {@code WorkflowStepExecutorExtension} SPI.</li>
 *     </ul>
 * State: Stateless (Enum)
 */
public enum NodeKind {
    AGENT, FUNCTION, WORKFLOW, CONDITION, ROUTER, LOOP, JOIN, PARALLEL, WEBHOOK;

    /** Returns the constant for the given string, case-insensitive; falls back to {@link #AGENT}. */
    public static NodeKind fromString(String value) {
        if (value == null) return AGENT;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }

    /** Bridges the flat-list {@link StepActionType} onto a node kind for graph backfill / migration. */
    public static NodeKind fromAction(StepActionType action) {
        if (action == null) return AGENT;
        return switch (action) {
            case AGENT, SEQUENTIAL -> AGENT;
            case CONDITION -> CONDITION;
            case PARALLEL -> PARALLEL;
            case WEBHOOK -> WEBHOOK;
            case LOOP -> LOOP;
            case ROUTER -> ROUTER;
            case JOIN -> JOIN;
            case FUNCTION -> FUNCTION;
            case WORKFLOW -> WORKFLOW;
        };
    }
}
