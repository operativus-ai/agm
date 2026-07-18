package com.operativus.agentmanager.compute.workflow.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.operativus.agentmanager.compute.workflow.WorkflowFunctionStepRegistry;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.spi.NodeContext;
import com.operativus.agentmanager.core.spi.WorkflowFunctionStep;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#FUNCTION} nodes
 *     (REQ-DR-5, DAG-6). Dispatches to the {@link WorkflowFunctionStep} named by the node's
 *     function key — stored in the step's {@code agent_id} column, mirroring how CONDITION
 *     repurposes it for its predicate. The function runs in-process and deterministically (no
 *     LLM); its returned content is threaded to all successors ({@code activePorts} stays null).
 *     A missing key, an unregistered key, or a throwing function yields a
 *     {@link StepOutput#failure} per the SPI contract, so the scheduler records and routes the
 *     failure instead of unwinding.
 * State: Stateless
 */
@Component
public class FunctionNodeExecutor implements WorkflowNodeExecutor {

    private final WorkflowFunctionStepRegistry functions;

    public FunctionNodeExecutor(WorkflowFunctionStepRegistry functions) {
        this.functions = functions;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.FUNCTION;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String key = ctx.node().getAgentId(); // FUNCTION repurposes agent_id for the function key
        if (key == null || key.isBlank()) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.FUNCTION, "FUNCTION",
                    "FUNCTION node has no function key (expected in the step's agent_id field)",
                    in.media(), started, Instant.now());
        }
        WorkflowFunctionStep fn = functions.byKey(key).orElse(null);
        if (fn == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.FUNCTION, "FUNCTION",
                    "No WorkflowFunctionStep registered for key '" + key
                            + "' (registered: " + functions.keys() + ")",
                    in.media(), started, Instant.now());
        }
        try {
            JsonNode content = fn.apply(in, ctx);
            return new StepOutput(in.nodeId(), in.nodeName(), NodeKind.FUNCTION, "FUNCTION",
                    content == null ? TextNode.valueOf("") : content, in.media(),
                    true, null, false, false, null, List.of(), started, Instant.now(), null, null, null, null);
        } catch (Exception e) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.FUNCTION, "FUNCTION",
                    "Function '" + key + "' failed: " + e.getMessage(),
                    in.media(), started, Instant.now());
        }
    }
}
