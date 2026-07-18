package com.operativus.agentmanager.control.dto;

/**
 * Domain Responsibility: Inbound payload for upserting an org's routing configuration.
 *     All fields are nullable — the row is created with defaults if absent, and any
 *     non-null field replaces the existing value on update. {@code defaultRouterAgentId}
 *     and {@code fallbackAgentId} are validated against the caller's org on the service
 *     side (cross-tenant rejection returns 404 per §79).
 * State: Stateless (Data Transfer Object)
 */
public record OrgRoutingConfigRequest(
        String defaultRouterAgentId,
        String fallbackAgentId,
        Boolean llmClassifierEnabled,
        Boolean ruleClassifierEnabled,
        String classifierModelId,
        Boolean semanticScoringEnabled
) {
}
