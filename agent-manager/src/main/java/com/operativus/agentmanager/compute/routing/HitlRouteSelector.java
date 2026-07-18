package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.model.RouterStepConfig;
import com.operativus.agentmanager.core.model.enums.RouteSelectorType;
import com.operativus.agentmanager.core.spi.RouteSelector;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: HITL-mode {@link RouteSelector} for REQ-DR-4 ROUTER
 *     steps. Returns the {@link RouteSelector#HITL_PENDING} sentinel
 *     unconditionally — the workflow dispatcher reads the sentinel and
 *     suspends the run with {@code RunStatus.AWAITING_ROUTE_SELECTION}
 *     instead of proceeding. The actual branch decision arrives via
 *     {@code POST /api/v1/workflows/runs/{runId}/continue}.
 *
 * State: Stateless (Spring bean)
 */
@Component
public class HitlRouteSelector implements RouteSelector {

    @Override
    public RouteSelectorType selectorType() {
        return RouteSelectorType.HITL;
    }

    @Override
    public String selectChoice(RouterStepConfig config, String priorStepOutput) {
        if (config == null) {
            throw new IllegalArgumentException("HitlRouteSelector requires non-null RouterStepConfig");
        }
        return HITL_PENDING;
    }
}
