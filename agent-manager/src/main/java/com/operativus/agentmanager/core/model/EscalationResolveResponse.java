package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/escalations/{escalationId}/resolve}.
 *   Carries the resolved escalation id, the underlying agent run id (so the caller can re-poll
 *   run status without a separate lookup), and the wire-form decision string ("APPROVED" or
 *   "REJECTED"). The endpoint always returns {@code 202 Accepted} with this body — the resume
 *   itself dispatches on a virtual thread and completes after the HTTP response has been sent.
 *   Sister surface to {@code POST /api/v1/approvals/{id}/resolve} (which returns
 *   {@code ApprovalDTO}); both came online in the 2026-05-09 chat-HITL restoration
 *   (PRs #406 + #407 + #408).
 * State: Immutable record.
 */
public record EscalationResolveResponse(String escalationId, String runId, String decision) {
}
