package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.RunStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Typed carrier for a single agent-run row served by
 * {@code GET /api/v1/runs}. Flattens the {@code AgentRun} entity down to the columns
 * the Runs page and Recent Activity widget need, without leaking JPA associations or
 * the full input/output text blobs (observability plan Phase 1 T006.3 + Phase 2 T007).
 *
 * <p>Null fields are legitimate — runs created before {@code RunTelemetryAccumulator}
 * started flushing (plan §5.13) have null token counts / cost / duration / error /
 * safety-risk. UI renders "—" for null (§4.3).
 * State: Immutable value carrier.
 */
public record AgentRunResponse(
        String id,
        String agentId,
        String sessionId,
        String userId,
        String orgId,
        String parentRunId,
        RunStatus status,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long durationMs,
        BigDecimal totalCostUsd,
        String errorType,
        BigDecimal safetyRiskScore,
        String orchestrationStrategy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
