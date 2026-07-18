package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/workflows/{id}/run}.
 *   Carries the BackgroundJob id of the asynchronously-dispatched workflow execution along with
 *   the workflow's stable identifiers ({@code workflowId} for the parent workflow, {@code sessionId}
 *   for the conversational chain). The endpoint always returns {@code 202 Accepted} with this body —
 *   actual workflow execution runs on the persistent job queue and completes after the HTTP
 *   response has been sent. Caller polls terminal state via {@code GET /api/v1/jobs/{jobId}}.
 * State: Immutable record.
 */
public record WorkflowExecutionResponse(String jobId, String workflowId, String sessionId) {
}
