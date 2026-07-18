package ai.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Domain Responsibility: Wire shape for one nested sub-workflow execution's node trace within a
 *     parent DAG run (REQ-DR-5, DAG-6) — surfaced over
 *     {@code GET /workflows/runs/{runId}/child-node-runs} so the run viewer can show what happened
 *     INSIDE a WORKFLOW node (child rows persist under a derived run id, not the parent's, so the
 *     plain {@code /node-runs} trace never includes them). One group per child invocation;
 *     {@link #parentNodeId} is the top-level WORKFLOW node the invocation hangs under (deeper
 *     descendants are attributed to the same top-level node, each as its own group).
 * State: Stateless (Immutable Record carrier)
 */
public record WorkflowChildNodeRunsDTO(
        String parentNodeId,
        String childRunId,
        String childWorkflowId,
        List<WorkflowNodeRunDTO> nodeRuns
) {}
