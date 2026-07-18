package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: Wire-format projection of a single row from
 * {@code orchestration_decisions} (logging plan §5.14). Exposed via the
 * read-side endpoints on {@code RunTelemetryController} for routing
 * forensics and decision-rate dashboards.
 * State: Immutable value carrier.
 */
public record OrchestrationDecisionDTO(
        Long id,
        String runId,
        String orgId,
        String strategy,
        String decisionType,
        String selectedAgentId,
        String rationale,
        Map<String, Object> decisionPayload,
        Instant createdAt) {
}
