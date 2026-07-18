package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/admin/agents/bulk-action}.
 *   Carries the BackgroundJob id of the asynchronously-dispatched bulk operation so the caller
 *   can poll terminal state via {@code GET /api/v1/jobs/{id}}. The endpoint always returns
 *   {@code 202 Accepted} with this body — the bulk action runs on a virtual thread and
 *   completes after the HTTP response has been sent.
 * State: Immutable record.
 */
public record BulkActionResponse(String jobId) {
}
