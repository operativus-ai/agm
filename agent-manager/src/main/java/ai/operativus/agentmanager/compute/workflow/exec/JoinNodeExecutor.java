package ai.operativus.agentmanager.compute.workflow.exec;

import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#JOIN} nodes
 *     (REQ-DR-5, DAG-6). An explicit fan-in barrier: the frontier scheduler's AND-join already
 *     holds the node until ALL predecessors complete and assembles their ordered outputs into the
 *     node's {@link StepInput#input} (joined upstream content). This executor performs no work — it
 *     threads that merged input forward as its output and activates every outgoing edge. It makes
 *     the join point explicit/addressable in the graph (versus an implicit multi-predecessor node).
 * State: Stateless
 */
@Component
public class JoinNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public NodeKind kind() {
        return NodeKind.JOIN;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        // The scheduler already merged all predecessor outputs into the input; thread it forward.
        return StepOutput.success(in.nodeId(), in.nodeName(), NodeKind.JOIN, "JOIN",
                in.inputText(), in.media(), started, Instant.now(), null, null);
    }
}
