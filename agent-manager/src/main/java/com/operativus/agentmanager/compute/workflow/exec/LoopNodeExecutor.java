package com.operativus.agentmanager.compute.workflow.exec;

import com.operativus.agentmanager.compute.routing.ConditionEvaluator;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.spi.NodeContext;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#LOOP} nodes
 *     (REQ-DR-5, DAG-5a). On each entry it decides whether to run the loop body again or to fall
 *     through, selecting the {@code "loop"} or {@code "exit"} outgoing port; the frontier scheduler
 *     drives the body via the loop edge and returns control through the back-edge until exit.
 *
 *     <p>Config lives in the step's {@code agent_id} column (mirroring the flat engine):
 *     {@code max:N} and/or {@code until:<expr>}, pipe-separated. The loop continues while
 *     {@code iteration < max} AND the {@code until} condition is not yet met. The {@code until}
 *     expression is evaluated by the shared {@link ConditionEvaluator} — the SAME grammar the flat
 *     engine's LOOP uses (contains/not_contains/length/empty plus {@code jsonpath:} and {@code llm:}),
 *     so a {@code jsonpath:}/{@code llm:} bound no longer silently fails to terminate on the DAG path.
 *     The current input is threaded through as the output content so the body (and, on exit, the
 *     continuation) receives the latest payload.
 * State: Stateless
 */
@Component
public class LoopNodeExecutor implements WorkflowNodeExecutor {

    private static final int DEFAULT_MAX = 1;

    private final ConditionEvaluator conditionEvaluator;

    public LoopNodeExecutor(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    @Override
    public NodeKind kind() {
        return NodeKind.LOOP;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String config = ctx.node().getAgentId();
        int max = parseMax(config);
        String until = parseUntil(config);
        int iteration = in.iteration() == null ? 0 : in.iteration();
        String input = in.inputText();

        boolean endByCondition = until != null && conditionEvaluator.evaluate(until, input);
        boolean again = iteration < max && !endByCondition;
        String port = again ? "loop" : "exit";

        return StepOutput.branched(in.nodeId(), in.nodeName(), NodeKind.LOOP, "LOOP",
                input, List.of(port), in.media(), started, Instant.now());
    }

    private static int parseMax(String config) {
        for (String part : split(config)) {
            String p = part.trim().toLowerCase();
            if (p.startsWith("max:")) {
                try { return Math.max(0, Integer.parseInt(part.trim().substring("max:".length()).trim())); }
                catch (NumberFormatException e) { return DEFAULT_MAX; }
            }
        }
        return DEFAULT_MAX;
    }

    private static String parseUntil(String config) {
        for (String part : split(config)) {
            if (part.trim().toLowerCase().startsWith("until:")) {
                return part.trim().substring("until:".length()).trim();
            }
        }
        return null;
    }

    private static String[] split(String config) {
        return config == null ? new String[0] : config.split("\\|");
    }
}
