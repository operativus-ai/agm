package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Wire-format response for
 *     {@code GET /api/v1/observability/aggregates/orchestration} (observability plan T031a).
 *     Combines a strategy-distribution rollup with an optional time-bucketed time-series so
 *     the Orchestration Analytics tab can render both a doughnut and a stacked bar chart
 *     from a single fetch.
 * State: Immutable value carrier.
 */
public record OrchestrationAggregateResponse(
        List<StrategyCount> distribution,
        List<TimeBucket> overTime) {

    /** A single strategy → count slice. */
    public record StrategyCount(String strategy, long count) {
    }

    /** A single time-bucket row: {@code perStrategy} maps strategy name → count
     *  for the bucket starting at {@code bucket} (UTC). */
    public record TimeBucket(Instant bucket, Map<String, Long> perStrategy) {
    }
}
