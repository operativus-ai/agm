package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Domain Responsibility: Wire-format response for
 *     {@code GET /api/v1/observability/aggregates/sessions} (observability plan T039a).
 *     Per-day rollup of session count, p50/p95 duration, and avg runs-per-session.
 *     Drives the Sessions Analytics tab's area chart, dual-line chart, and KPI card.
 * State: Immutable value carrier.
 */
public record SessionAggregateResponse(List<Bucket> buckets) {

    /**
     * One UTC-day bucket. {@code day} is the bucket start (midnight UTC).
     * {@code p50DurationSeconds} / {@code p95DurationSeconds} are the percentile
     * session durations in seconds for sessions whose {@code created_at} falls in
     * the bucket. {@code avgRunsPerSession} is the per-bucket mean of in-window
     * runs-per-session.
     */
    public record Bucket(
            Instant day,
            long sessionCount,
            double p50DurationSeconds,
            double p95DurationSeconds,
            double avgRunsPerSession) {
    }
}
