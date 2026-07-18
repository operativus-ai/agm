package ai.operativus.agentmanager.compute.workflow.exec;

import ai.operativus.agentmanager.compute.routing.ConditionEvaluator;
import ai.operativus.agentmanager.core.model.enums.NodeKind;
import ai.operativus.agentmanager.core.model.workflow.StepInput;
import ai.operativus.agentmanager.core.model.workflow.StepOutput;
import ai.operativus.agentmanager.core.spi.NodeContext;
import ai.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#CONDITION} nodes
 *     (REQ-DR-5, DAG-4a). Evaluates the node's predicate (stored in the step's {@code agent_id}
 *     column, mirroring the flat engine) against the incoming content and selects the outgoing
 *     <b>port</b> — {@code "true"} or {@code "false"} — so the frontier scheduler activates only the
 *     matching branch instead of every edge. The input is threaded through unchanged as the output
 *     content, so the taken branch's first node receives the data, not the boolean.
 *
 *     <p><b>Grammar.</b> Delegates to the shared {@link ConditionEvaluator} — the SAME grammar the
 *     flat engine uses, including {@code jsonpath:} (Jayway) and {@code llm:} (yes/no judgment).
 *     This closed an earlier divergence where the DAG path silently treated those prefixes as
 *     {@code true} and mis-routed.
 * State: Stateless
 */
@Component
public class ConditionNodeExecutor implements WorkflowNodeExecutor {

    private final ConditionEvaluator conditionEvaluator;

    public ConditionNodeExecutor(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.CONDITION;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String expr = ctx.node().getAgentId(); // CONDITION repurposes agent_id for the predicate
        String input = in.inputText();
        boolean met = conditionEvaluator.evaluate(expr, input);
        String port = met ? "true" : "false";
        // Thread the input through so the taken branch sees the data, not the boolean.
        return StepOutput.branched(in.nodeId(), in.nodeName(), NodeKind.CONDITION, "CONDITION",
                input, List.of(port), in.media(), started, Instant.now());
    }
}
