package com.operativus.agentmanager.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import com.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Acts as an immutable data transfer object recording the execution history and final output of a specific scheduled run instance.
 * State: Stateless (Immutable Record carrier)
 */
public record ScheduleRunDTO(
        String id,
        String scheduleId,
        RunStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage,
        JsonNode output,
        /**
         * Structured pointer to the agent run produced by this schedule when its target
         * is AGENT or TEAM. Null for WORKFLOW targets and for runs that never made it
         * past dispatch (FAILED before agent_runs row created). Populated by
         * {@code ScheduleExecutionPoller.executeAndPersist}. Mirrors the existing
         * {@code workflowRunId} column on the entity (which the DTO doesn't yet
         * surface — out of scope here).
         */
        String agentRunId
) {}
