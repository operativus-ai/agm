package com.operativus.agentmanager.compute.workflow.exec;

import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.spi.NodeContext;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#PARALLEL} nodes
 *     (REQ-DR-5, DAG-6). An explicit fan-out gate: it performs no work itself, threads its input
 *     through unchanged, and activates EVERY outgoing edge so the frontier scheduler dispatches all
 *     successor branches concurrently (real fan-out on virtual threads). The actual parallelism is
 *     the scheduler's — this node simply makes the fan-out point explicit and addressable in the
 *     graph (versus relying on a node having multiple out-edges). Returning a success with a null
 *     {@code activePorts} means "no port restriction", i.e. every edge carries a live token.
 * State: Stateless
 */
@Component
public class ParallelNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public NodeKind kind() {
        return NodeKind.PARALLEL;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        // No-op gate: thread the input forward; success() leaves activePorts null → all edges fire.
        return StepOutput.success(in.nodeId(), in.nodeName(), NodeKind.PARALLEL, "PARALLEL",
                in.inputText(), in.media(), started, Instant.now(), null, null);
    }
}
