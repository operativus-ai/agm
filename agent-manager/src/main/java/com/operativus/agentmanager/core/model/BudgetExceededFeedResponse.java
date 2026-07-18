package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Cursor-paginated wire-format response for
 *     {@code GET /api/observability/budget-exceeded-feed} (observability plan T030a).
 *     The frontend dashboard banner polls this endpoint every 60s, advancing
 *     {@code nextCursor} on each successful call so it only receives newly recorded
 *     budget breaches.
 * State: Immutable value carrier.
 */
public record BudgetExceededFeedResponse(
        List<BudgetExceededEvent> events,
        Instant nextCursor) {

    /**
     * Domain Responsibility: Slim projection of an {@code agent_run_events} row whose
     *     {@code event_type} is {@code BUDGET_EXCEEDED}. Exposes the run linkage and
     *     payload (limit + actual cost details) the dashboard needs without leaking
     *     unrelated columns.
     * State: Immutable value carrier.
     */
    public record BudgetExceededEvent(
            Long id,
            String runId,
            String agentId,
            Map<String, Object> payload,
            Instant eventTs) {
    }
}
