package com.operativus.agentmanager.control.dto.composio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/admin/composio/actions/{id}}. Carries
 * {@code version} for optimistic-lock validation — the service compares against
 * the current {@code @Version} on the entity and surfaces a 409 on mismatch.
 *
 * <p>The {@code actionName} is intentionally absent: rename via PUT would require
 * deriving a new id, breaking referential identity. Operators delete-then-create
 * if a rename is needed.
 */
public record ComposioActionConfigUpdateRequest(
        @Min(1) @Max(3) int tier,
        boolean enabled,
        @NotNull Integer version) {
}
