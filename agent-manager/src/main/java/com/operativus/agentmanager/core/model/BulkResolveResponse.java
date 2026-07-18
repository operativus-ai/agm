package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/approvals/bulk-resolve}.
 *   Reports per-call resolution outcomes — both counts always present, no nulls. {@code resolved}
 *   counts approvals that completed successfully; {@code failed} counts ids that threw any
 *   exception during the per-row REQUIRES_NEW transaction (missing rows, cross-tenant ids,
 *   already-resolved rows, payload-hash mismatches all collapse into this bucket so a probe
 *   for tenant-membership is not feasible).
 * State: Immutable record.
 */
public record BulkResolveResponse(int resolved, int failed) {
}
