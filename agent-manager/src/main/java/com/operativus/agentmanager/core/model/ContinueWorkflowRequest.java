package com.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for
 *   {@code POST /api/v1/workflows/runs/{runId}/continue}. Carries the
 *   {@code choiceKey} selected by the caller in response to a HITL ROUTER
 *   step suspension (REQ-DR-4). The key must match one of the keys in the
 *   paused step's {@code routerConfig.choices} map; the workflow then
 *   branches to the corresponding target step and resumes execution.
 *
 * State: Immutable record.
 */
public record ContinueWorkflowRequest(
        @NotBlank String choiceKey
) {
}
