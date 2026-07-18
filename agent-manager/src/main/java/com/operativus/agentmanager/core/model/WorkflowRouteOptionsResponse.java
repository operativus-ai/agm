package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.RunStatus;

import java.util.List;

/**
 * Domain Responsibility: Typed carrier for {@code GET /api/v1/workflows/runs/{runId}/route-options}.
 * Tells the UI whether a run is paused at a ROUTER HITL gate
 * ({@code awaitingRouteSelection} / {@link RunStatus#AWAITING_ROUTE_SELECTION}) and, if so, the
 * declared {@code choiceKeys} (the router step's {@code routerConfig.choices} keys) the operator
 * may pick to resume via {@code POST /runs/{runId}/continue}, plus the optional {@code defaultChoice}.
 * For runs not awaiting a route selection, {@code choiceKeys} is empty.
 * State: Immutable value carrier.
 */
public record WorkflowRouteOptionsResponse(
        String runId,
        RunStatus status,
        boolean awaitingRouteSelection,
        List<String> choiceKeys,
        String defaultChoice) {
}
