package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from
 *   {@code POST /api/v1/workflows/runs/{runId}/continue}. Carries the
 *   BackgroundJob id of the asynchronously-dispatched continue operation
 *   alongside the {@code runId} being resumed from
 *   {@code RunStatus.AWAITING_ROUTE_SELECTION}. The endpoint always returns
 *   {@code 202 Accepted}; the actual branch-and-resume runs on the persistent
 *   job queue. Caller polls terminal state via {@code GET /api/v1/jobs/{jobId}}.
 * State: Immutable record.
 */
public record WorkflowContinueResponse(String jobId, String runId) {
}
