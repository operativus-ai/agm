package ai.operativus.agentmanager.core.model;

/**
 * Request body for relabeling a DAG edge's port (REQ-DR-5). Only the {@code condition}
 * (port label) is mutable — an edge's endpoints are fixed; to re-route, delete and re-draw.
 * {@code null}/blank = unconditional, {@code "true"}/{@code "false"} = CONDITION branches,
 * {@code "loop"}/{@code "exit"}/{@code "back"} = LOOP ports, a choice key = ROUTER branch.
 */
public record UpdateWorkflowEdgeRequest(String condition) {}
