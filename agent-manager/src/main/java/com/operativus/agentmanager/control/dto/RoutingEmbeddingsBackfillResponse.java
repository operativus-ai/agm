package com.operativus.agentmanager.control.dto;

/**
 * Domain Responsibility: Outbound summary for the admin backfill endpoint
 *     ({@code POST /api/v1/admin/routing-embeddings/backfill}). Reports how many active
 *     agents were considered and how many embeddings were written to {@code routing_vectors}
 *     (agents without a non-blank description are skipped).
 * State: Stateless (Data Transfer Object)
 */
public record RoutingEmbeddingsBackfillResponse(
        String orgId,
        int totalAgents,
        int embedded
) {
}
