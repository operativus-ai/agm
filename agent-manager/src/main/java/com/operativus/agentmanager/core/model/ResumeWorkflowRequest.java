package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format request body for
 *   {@code POST /api/v1/workflows/runs/{runId}/resume}. Carries the human-approved output
 *   to inject into the next workflow step on resume. Optional — null/missing falls back to
 *   empty string at the handler.
 *
 * <p>Documented as a HITL pause/resume feature in {@code docs/old/features/agm-workflows.md};
 * FE caller {@code orchestrationApi.resumeWorkflowRun} exists as an API def but has zero
 * direct UI callers on {@code main} (verified during T022, PR #418). Promotion preserves
 * documented future-UI intent.
 * State: Immutable record.
 */
public record ResumeWorkflowRequest(
        String output
) {
}
