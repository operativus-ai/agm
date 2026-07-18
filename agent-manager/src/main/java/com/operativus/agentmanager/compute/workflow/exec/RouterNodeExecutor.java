package com.operativus.agentmanager.compute.workflow.exec;

import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.NodeKind;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.model.workflow.StepInput;
import com.operativus.agentmanager.core.model.workflow.StepOutput;
import com.operativus.agentmanager.core.spi.NodeContext;
import com.operativus.agentmanager.core.spi.RouteSelector;
import com.operativus.agentmanager.core.spi.WorkflowNodeExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: {@link WorkflowNodeExecutor} for {@link NodeKind#ROUTER} nodes
 *     (REQ-DR-4 / REQ-DR-5, DAG-4c). Resolves the node's {@link RouterStepConfig} to a single
 *     choice key via the matching {@link RouteSelector} SPI bean — the SAME selectors the flat
 *     engine uses (RULE / LLM / HITL) — and emits that key as the outgoing <b>port</b>, so the
 *     frontier scheduler activates only the edge labelled with it. Non-selected branches are
 *     pruned by the dead-path elimination already in the scheduler (DAG-4b).
 *
 *     <p>The selector's {@code HITL_PENDING} sentinel bubbles up as a paused output
 *     ({@code pauseKind="route"}); the run pauses, and resuming a paused DAG node (re-entering the
 *     ROUTER with the operator's choice) lands with frontier resume (DAG-3c). A {@code null} choice
 *     with no default fails the node.
 * State: Stateless
 */
@Component
public class RouterNodeExecutor implements WorkflowNodeExecutor {

    private final Map<RouteSelectorType, RouteSelector> selectorByType = new EnumMap<>(RouteSelectorType.class);

    public RouterNodeExecutor(List<RouteSelector> selectors) {
        for (RouteSelector s : selectors) {
            selectorByType.putIfAbsent(s.selectorType(), s);
        }
    }

    @Override
    public NodeKind kind() {
        return NodeKind.ROUTER;
    }

    @Override
    public StepOutput execute(StepInput in, NodeContext ctx) {
        Instant started = Instant.now();
        String input = in.inputText();
        RouterStepConfig config = ctx.node().getRouterConfig();
        if (config == null || config.selectorType() == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.ROUTER, "ROUTER",
                    "ROUTER node missing router_config / selectorType", in.media(), started, Instant.now());
        }
        RouteSelector selector = selectorByType.get(config.selectorType());
        if (selector == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.ROUTER, "ROUTER",
                    "No RouteSelector registered for type " + config.selectorType(), in.media(), started, Instant.now());
        }

        String choice = selector.selectChoice(config, input);
        if (RouteSelector.HITL_PENDING.equals(choice)) {
            // Carry the router's input as the paused content so a DAG-3c resume can thread it to the
            // operator-chosen branch (the non-HITL path threads it via branched(input, ...); the bare
            // paused() factory would drop it as empty, leaving the chosen branch with no input).
            return new StepOutput(in.nodeId(), in.nodeName(), NodeKind.ROUTER, "ROUTER",
                    com.fasterxml.jackson.databind.node.TextNode.valueOf(input == null ? "" : input),
                    in.media(), false, null, false, true,
                    com.operativus.agentmanager.core.model.workflow.PauseKind.ROUTE, java.util.List.of(),
                    started, Instant.now(), null, null, null, null);
        }
        if (choice == null) {
            return StepOutput.failure(in.nodeId(), in.nodeName(), NodeKind.ROUTER, "ROUTER",
                    "ROUTER produced no choice and no defaultChoice", in.media(), started, Instant.now());
        }
        // Thread the input through; the selected choice key is the activated outgoing port.
        return StepOutput.branched(in.nodeId(), in.nodeName(), NodeKind.ROUTER, "ROUTER",
                input, List.of(choice), in.media(), started, Instant.now());
    }
}
