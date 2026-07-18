package com.operativus.agentmanager.control.dto;

import com.operativus.agentmanager.core.entity.RoutingDecisionEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Outbound projection of one {@link RoutingDecisionEntity} row for the
 *     admin telemetry endpoint. The {@code messageHash} is exposed (SHA-256 hex) so admins
 *     can deduplicate identical-message decisions across users without seeing the message
 *     content itself.
 * State: Stateless (Data Transfer Object)
 */
public record RoutingDecisionResponse(
        String id,
        String orgId,
        String userId,
        String sessionId,
        String messageHash,
        Integer messageLength,
        String resolvedAgentId,
        String resolutionStatus,
        String strategyUsed,
        BigDecimal confidence,
        Integer latencyMs,
        Integer candidateCount,
        String rationale,
        String traceId,
        LocalDateTime createdAt
) {
    public static RoutingDecisionResponse from(RoutingDecisionEntity e) {
        return new RoutingDecisionResponse(
                e.getId(), e.getOrgId(), e.getUserId(), e.getSessionId(),
                e.getMessageHash(), e.getMessageLength(),
                e.getResolvedAgentId(),
                e.getResolutionStatus() == null ? null : e.getResolutionStatus().name(),
                e.getStrategyUsed() == null ? null : e.getStrategyUsed().name(),
                e.getConfidence(), e.getLatencyMs(), e.getCandidateCount(),
                e.getRationale(), e.getTraceId(), e.getCreatedAt());
    }
}
