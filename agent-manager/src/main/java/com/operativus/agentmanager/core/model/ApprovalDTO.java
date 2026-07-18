package com.operativus.agentmanager.core.model;

import java.time.LocalDateTime;
import com.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Acts as an immutable data transfer object defining a manual human-in-the-loop (HITL) approval request for tool execution.
 * State: Stateless (Immutable Record carrier)
 */
public record ApprovalDTO(
        String id,
        String runId,
        String workflowRunId,
        String agentId,
        String toolName,
        String toolArguments,
        RunStatus status, // PENDING, APPROVED, REJECTED, EXPIRED
        String requestedBy,
        String resolvedBy,
        String contextualMessage,
        String decisionTier,
        String reasoningTrace,
        String impactAssessment,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {}
