package ai.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for both
 *   {@code POST /api/v1/approvals/{id}/resolve} and
 *   {@code POST /api/v1/escalations/{escalationId}/resolve}. Carries the human's
 *   APPROVE/REJECT decision string. Each handler additionally validates the value:
 *   the approvals path delegates to the service which throws on unknown values; the
 *   escalations path explicitly rejects anything other than {@code APPROVED} or
 *   {@code REJECTED} via {@code RunStatus.fromValue}. Reused across both endpoints
 *   because the wire shape and semantics are identical.
 * State: Immutable record.
 */
public record ResolveDecisionRequest(
        @NotBlank String decision
) {
}
