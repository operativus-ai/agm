package com.operativus.agentmanager.control.dto.composio;

import com.operativus.agentmanager.core.entity.ComposioActionConfig;

/**
 * Response shape for action-config read/write. Carries the JPA-managed
 * {@code version} so update callers can echo it back for optimistic-lock checks.
 */
public record ComposioActionConfigResponse(
        String id,
        String actionName,
        String llmToolName,
        int tier,
        boolean enabled,
        Integer version,
        String createdAt,
        String updatedAt,
        String createdBy,
        String updatedBy) {

    public static ComposioActionConfigResponse from(ComposioActionConfig entity) {
        return new ComposioActionConfigResponse(
                entity.getId(),
                entity.getActionName(),
                entity.getLlmToolName(),
                entity.getTier(),
                entity.isEnabled(),
                entity.getVersion(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null,
                entity.getCreatedBy(),
                entity.getUpdatedBy());
    }
}
