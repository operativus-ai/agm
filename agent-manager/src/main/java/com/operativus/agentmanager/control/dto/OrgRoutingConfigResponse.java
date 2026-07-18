package com.operativus.agentmanager.control.dto;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Outbound wire shape for the per-org routing configuration row.
 * State: Stateless (Data Transfer Object)
 */
public record OrgRoutingConfigResponse(
        String id,
        String orgId,
        String defaultRouterAgentId,
        String fallbackAgentId,
        Boolean llmClassifierEnabled,
        Boolean ruleClassifierEnabled,
        String classifierModelId,
        Boolean semanticScoringEnabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
