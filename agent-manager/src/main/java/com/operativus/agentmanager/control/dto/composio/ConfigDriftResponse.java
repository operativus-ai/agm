package com.operativus.agentmanager.control.dto.composio;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Domain Responsibility: Read-only snapshot of config drift between the live Composio action
 * registry and the DB-backed config tables. Returned by the SUPER_ADMIN drift endpoint.
 * State: Stateless record — immutable once constructed.
 */
public record ConfigDriftResponse(
        Instant generatedAt,
        String registrySource,
        boolean registryWasTruncated,
        ActionDrift actionDrift,
        List<ConnectionRow> connections,
        List<String> orgsWithoutConnection
) {
    public record ActionDrift(
            int totalInDb,
            int enabledInDb,
            int inLiveRegistry,
            List<String> inRegistryNotInDb,
            List<String> inDbDisabled,
            List<String> inSync
    ) {}

    public record ConnectionRow(
            String orgId,
            String connectionId,
            LocalDateTime updatedAt
    ) {}
}
