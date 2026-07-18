package ai.operativus.agentmanager.control.dto.composio;

import ai.operativus.agentmanager.core.entity.ComposioConnectionConfig;

/**
 * Response shape for {@code /api/admin/composio/connection} GET/PUT. Carries the
 * JPA-managed {@code version} so update callers can echo it back for optimistic-lock
 * checks on the next PUT.
 */
public record ComposioConnectionConfigResponse(
        String id,
        String orgId,
        String connectionId,
        Integer version,
        String createdAt,
        String updatedAt) {

    public static ComposioConnectionConfigResponse from(ComposioConnectionConfig entity) {
        return new ComposioConnectionConfigResponse(
                entity.getId(),
                entity.getOrgId(),
                entity.getConnectionId(),
                entity.getVersion(),
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
    }
}
