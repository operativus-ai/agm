package com.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * A single step node's saved canvas position (REQ-DR-5). {@code stepId} is the
 * {@link com.operativus.agentmanager.core.entity.WorkflowStep} id; {@code x}/{@code y} are
 * React Flow canvas coordinates.
 */
public record NodePosition(@NotBlank String stepId, double x, double y) {}
