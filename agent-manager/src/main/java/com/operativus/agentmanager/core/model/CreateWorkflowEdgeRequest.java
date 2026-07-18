package com.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for adding a DAG edge between two steps of a workflow (REQ-DR-5).
 * {@code condition} is the optional port label (null = unconditional next-step).
 */
public record CreateWorkflowEdgeRequest(
        @NotBlank String fromStepId,
        @NotBlank String toStepId,
        String condition) {}
