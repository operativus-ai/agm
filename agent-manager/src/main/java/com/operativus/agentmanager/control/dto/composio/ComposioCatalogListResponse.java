package com.operativus.agentmanager.control.dto.composio;

import com.operativus.agentmanager.core.model.ComposioCatalogAction;

import java.util.List;

/**
 * Domain Responsibility: Wire-format response for
 *   {@code GET /api/admin/composio/catalog}. Normalized envelope around whatever
 *   Composio's upstream API returned (v2 uses a top-level array or {@code {items: []}};
 *   v3 uses {@code {items, totalItems, nextCursor}}). {@link ComposioCatalogClient}
 *   does the upstream-shape adaptation; this DTO is the AGM-stable contract the FE
 *   consumes.
 *
 * State: Stateless (Immutable Record carrier)
 */
public record ComposioCatalogListResponse(
        List<ComposioCatalogAction> items,
        int totalReturned,
        String appFilter
) {}
