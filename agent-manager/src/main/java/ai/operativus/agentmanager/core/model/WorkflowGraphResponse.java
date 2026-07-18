package ai.operativus.agentmanager.core.model;

import java.util.List;

/**
 * The full DAG view of a workflow — its step nodes plus the explicit edges between them
 * (REQ-DR-5). Edges are empty for legacy flat-list workflows that still dispatch by
 * {@code step_order}; the FE graph editor derives sequential edges in that case.
 */
public record WorkflowGraphResponse(List<WorkflowStepDTO> steps, List<WorkflowEdgeDTO> edges) {}
