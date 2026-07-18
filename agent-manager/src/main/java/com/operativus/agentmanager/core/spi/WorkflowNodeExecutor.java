package com.operativus.agentmanager.core.spi;

import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.workflow.NestedPause;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Strategy SPI for executing one node of a DAG workflow (DAG plan §2.4).
 *     The frontier scheduler ({@code DagWorkflowExecutor}, DAG-3) dispatches each ready node to
 *     the executor whose {@link #kind()} matches the node's {@link NodeKind}. One implementation
 *     per kind: {@code AgentNodeExecutor} (this phase), then Function/Condition/Router/Loop/Join/
 *     SubWorkflow in later phases. Executors are stateless Spring beans; all per-invocation state
 *     arrives via {@link StepInput} + {@link NodeContext} and leaves via {@link StepOutput}.
 * State: Stateless
 */
public interface WorkflowNodeExecutor {

    /** The node kind this executor handles. The scheduler resolves executors by this key. */
    NodeKind kind();

    /**
     * Executes the node.
     *
     * @param in  the structured input (canonical input, addressable upstream outputs, media, state)
     * @param ctx the run/node context (identity, tenancy, session, the node's own config)
     * @return the structured output, including any control-flow signal (stop / paused) the
     *         scheduler must act on. Implementations should not throw for ordinary node failures —
     *         return {@link StepOutput#failure} so the scheduler can record and route it.
     */
    StepOutput execute(StepInput in, NodeContext ctx);

    /**
     * Settles a NESTED pause recorded by this executor (a paused child sub-workflow): re-enter the
     * child's exact graph from {@code nested.childFrontier()}, retargeting {@code settledInner}
     * (built by the control layer against the PARENT node id) onto the child's innermost paused
     * node. Only kinds that emit {@link StepOutput#pausedNested} (WORKFLOW) override this; the
     * default records a failure so a mismatched dispatch never silently passes.
     *
     * @return the node's new output — success when the child completed, paused (with a fresh
     *         {@code nested}) when it paused again, failure when it failed
     */
    default StepOutput resumeNested(StepInput in, NodeContext ctx, NestedPause nested, StepOutput settledInner) {
        return StepOutput.failure(in.nodeId(), in.nodeName(), kind(), null,
                "Node kind " + kind() + " does not support nested resume",
                List.of(), Instant.now(), Instant.now());
    }
}
