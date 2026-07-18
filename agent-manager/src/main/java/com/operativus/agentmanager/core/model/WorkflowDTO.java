package com.operativus.agentmanager.core.model;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object carrying metadata for a predefined sequence of agent tasks (a Workflow).
 * State: Stateless (Immutable Record carrier)
 */
public record WorkflowDTO(
        String id,
        String name,
        String description,
        int stepCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
