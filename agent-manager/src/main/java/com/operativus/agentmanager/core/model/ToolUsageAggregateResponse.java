package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Wire-format response for
 *     {@code GET /api/v1/observability/aggregates/tools} (observability plan T035b).
 *     Combines a per-tool rollup (count + error rate + avg duration) with an optional
 *     time-bucketed stacked series for the Tools Analytics tab.
 * State: Immutable value carrier.
 */
public record ToolUsageAggregateResponse(
        List<ToolStat> tools,
        List<TimeBucket> overTime) {

    /** Per-tool rollup: total invocations, error count, average duration in ms. */
    public record ToolStat(
            String toolName,
            long totalCount,
            long errorCount,
            double avgDurationMs) {
    }

    /** Time-bucketed slice: maps tool name → invocation count for the bucket starting
     *  at {@code bucket} (UTC). */
    public record TimeBucket(Instant bucket, Map<String, Long> perTool) {
    }
}
