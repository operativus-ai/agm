package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.JobStatus;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Typed carrier for a single background-job row served by
 * {@code GET /api/v1/observability/background-jobs} (observability plan Phase 1 T004).
 * Exposes only what the monitor UI needs — omits the opaque {@code payload} /
 * {@code result} text blobs to keep the list response small.
 * State: Immutable value carrier.
 */
public record BackgroundJobResponse(
        String id,
        String agentId,
        String jobType,
        JobStatus status,
        int retryCount,
        int maxRetries,
        String errorMessage,
        String priority,
        LocalDateTime nextRetryAt,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime lockedAt) {
}
