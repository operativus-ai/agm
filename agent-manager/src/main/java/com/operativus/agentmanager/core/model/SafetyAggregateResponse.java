package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Wire-format response for
 *     {@code GET /api/v1/observability/aggregates/safety} (observability plan T033a).
 *     Combines a per-agent / per-day heatmap with a flagged-runs top-N for the Safety
 *     Analytics tab.
 * State: Immutable value carrier.
 */
public record SafetyAggregateResponse(
        List<HeatmapCell> cells,
        List<FlaggedRun> flaggedRunsTopN) {

    /** A single heatmap cell: per-agent / per-day rollup. */
    public record HeatmapCell(
            String agentId,
            Instant day,
            double avgScore,
            double maxScore,
            long flagged,
            long total) {
    }

    /** A flagged run entry — minimal projection for the table. */
    public record FlaggedRun(
            String runId,
            String agentId,
            double score,
            Instant createdAt) {
    }
}
