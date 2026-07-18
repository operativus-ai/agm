package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.RunStatus;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Typed carrier for a single workflow-run row served by
 * {@code GET /api/v1/workflows/{workflowId}/runs}. Flattens the {@code WorkflowRun}
 * entity and pre-computes {@code durationMs} so the UI doesn't have to subtract
 * timestamps (observability plan Phase 1 T003).
 * State: Immutable value carrier.
 */
public record WorkflowRunResponse(
        String id,
        String workflowId,
        String sessionId,
        RunStatus status,
        Integer lastStepOrder,
        Long durationMs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
