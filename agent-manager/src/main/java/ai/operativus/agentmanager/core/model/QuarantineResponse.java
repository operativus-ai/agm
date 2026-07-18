package ai.operativus.agentmanager.core.model;

import java.time.Instant;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/admin/agents/{id}/quarantine}
 *   and {@code /unquarantine}. Returns the actor-meaningful side effects of the atomic
 *   transaction so the operator UI can render confirmation without a follow-up GET.
 * State: Immutable record.
 *
 * <p>{@code alreadyQuarantined=true} is the idempotent no-op signal — the action was a hit on
 * an already-set state. UI should treat that as success (don't error-toast); operator
 * intentionally re-confirmed an existing quarantine.
 */
public record QuarantineResponse(
        String agentId,
        int runsCancelled,
        int credentialsLocked,
        Instant quarantinedAt,
        boolean alreadyQuarantined
) {
}
