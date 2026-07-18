package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.WorkflowNodeRun;
import ai.operativus.agentmanager.core.model.enums.NodeKind;

import java.time.Instant;

/**
 * Domain Responsibility: Wire shape for one node execution in a DAG workflow run (REQ-DR-5) —
 *     the per-node trace surfaced over {@code GET /workflows/runs/{runId}/node-runs} so the UI can
 *     render what the frontier scheduler actually ran (node, outcome, per-node token cost/model).
 * State: Stateless (Immutable Record carrier)
 */
public record WorkflowNodeRunDTO(
        String id,
        String nodeId,
        String nodeName,
        NodeKind kind,
        boolean success,
        String error,
        boolean paused,
        String pauseKind,
        String content,
        Long tokenCost,
        String modelId,
        Instant startedAt,
        Instant endedAt
) {
    public static WorkflowNodeRunDTO from(WorkflowNodeRun r) {
        return new WorkflowNodeRunDTO(
                r.getId(), r.getNodeId(), r.getNodeName(), r.getKind(),
                r.isSuccess(), r.getError(), r.isPaused(), r.getPauseKind(),
                r.getContent(), r.getTokenCost(), r.getModelId(),
                r.getStartedAt(), r.getEndedAt());
    }
}
