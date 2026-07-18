package ai.operativus.agentmanager.compute.security;

/**
 * Domain Responsibility: Wire shape for a single PII-scrub audit entry served to the Security
 *     admin viewer (GET /api/v1/pii-policies/audit-log). Mirrors the frontend {@code PiiAuditLogEntry}
 *     type. {@code orgId} is deliberately NOT exposed — it is an internal tenant-scoping column only.
 * State: Immutable (record / data carrier).
 */
public record PiiAuditLogDTO(
        String id,
        String agentId,
        String policyName,
        String scrubStrategy,
        int occurrences,
        String sessionId,
        String createdAt) {

    public static PiiAuditLogDTO from(PiiAuditLogEntity e) {
        return new PiiAuditLogDTO(
                e.getId() != null ? e.getId().toString() : null,
                e.getAgentId(),
                e.getPolicyName(),
                e.getScrubStrategy(),
                e.getOccurrences(),
                e.getSessionId(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
    }
}
