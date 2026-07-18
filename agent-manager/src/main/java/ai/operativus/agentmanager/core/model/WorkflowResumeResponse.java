package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/workflows/runs/{runId}/resume}.
 *   Carries the BackgroundJob id of the asynchronously-dispatched resume operation alongside the
 *   {@code runId} being resumed. The endpoint always returns {@code 202 Accepted} with this body —
 *   the actual resume runs on the persistent job queue and completes after the HTTP response has
 *   been sent. Caller polls terminal state via {@code GET /api/v1/jobs/{jobId}}.
 * State: Immutable record.
 */
public record WorkflowResumeResponse(String jobId, String runId) {
}
