package ai.operativus.agentmanager.core.model;

import java.time.Instant;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/admin/incident/halt-all-runs}.
 *   Reports the scope of the global cancellation so the operator UI can render the post-incident
 *   confirmation banner (e.g. "halted 17 runs across 4 tenants at 12:34 UTC").
 * State: Immutable record.
 */
public record HaltAllRunsResponse(
        int runsCancelled,
        int tenantsAffected,
        Instant haltedAt
) {
}
