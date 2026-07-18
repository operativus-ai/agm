package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format request body for {@code POST /api/v1/workflows/{id}/run}.
 *   Carries the initial input text for the workflow (optional — null/missing falls back to
 *   empty string at the handler) and an optional {@code sessionId} to bind this run into an
 *   existing conversational chain (null/missing → handler generates a fresh random UUID).
 *   Both fields nullable to preserve the prior {@code Map.getOrDefault} behavior — wire shape
 *   is unchanged.
 *
 * <p>Documented as a feature in {@code docs/old/features/agm-overview-all-features.md}; FE
 * caller {@code orchestrationApi.runWorkflow} exists as an API def but has zero direct UI
 * callers on {@code main} (verified during T022, PR #418). Promotion preserves documented
 * future-UI intent.
 * State: Immutable record.
 */
public record ExecuteWorkflowRequest(
        String input,
        String sessionId
) {
}
