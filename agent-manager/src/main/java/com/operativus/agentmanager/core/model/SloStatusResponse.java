package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Typed carrier for a single SLO compliance row served by
 * {@code GET /api/v1/observability/slo-status}. Mirrors the shape of each entry produced
 * by {@code SloTrackingService#getSloStatus()}, but pins field names so the frontend
 * TypeScript type compiles without casting.
 * State: Immutable value carrier.
 */
public record SloStatusResponse(
        String sloName,
        double target,
        double current,
        boolean compliant,
        String unit) {
}
