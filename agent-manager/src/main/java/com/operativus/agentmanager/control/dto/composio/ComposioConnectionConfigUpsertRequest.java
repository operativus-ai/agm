package com.operativus.agentmanager.control.dto.composio;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PUT /api/admin/composio/connection}. Carries the Composio
 * bundled-OAuth connectionId for the caller's org. {@code version} is null on first
 * create and required (non-null) on update — the service distinguishes the two paths
 * by querying for an existing row first, so creates and updates share one endpoint.
 *
 * <p>Notably absent: {@code orgId}. The caller's org is resolved server-side from the
 * authenticated principal so an authenticated admin cannot upsert into a different org.
 *
 * @param connectionId the Composio connection identifier (operator-supplied, opaque).
 * @param version      JPA-managed optimistic-lock token. Null on create; non-null and
 *                     equal to the existing row's {@code @Version} on update.
 */
public record ComposioConnectionConfigUpsertRequest(
        @NotBlank String connectionId,
        Integer version) {
}
