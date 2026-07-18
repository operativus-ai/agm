package com.operativus.agentmanager.core.model;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object defining a cron-based execution schedule for a target agent or workflow.
 * State: Stateless (Immutable Record carrier)
 *
 * <p>{@code lastRunAt} is the {@code startedAt} of the most recent {@code schedule_runs}
 * row for this schedule (null if never executed). {@code nextRunAt} is computed at
 * read time from the cron expression via Spring's
 * {@link org.springframework.scheduling.support.CronExpression}; it is null when the
 * schedule is inactive or the cron has no future trigger from the current instant.
 * Neither field is persisted on the entity — both are server-computed projections.
 */
public record ScheduleDTO(
        String id,
        String name,
        String description,
        String cronExpression,
        String targetType,
        String targetId,
        String resumeSessionId,
        String contextualPrompt,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastRunAt,
        LocalDateTime nextRunAt,
        Long version,
        /**
         * F6 — DAG dependency. When set, the poller fires this schedule only after the
         * parent schedule's most-recent run is COMPLETED. Validated at create/update for
         * existence in the same org and for absence of cycles
         * ({@code ScheduleService.validateDependencyChain}). Null means no dependency.
         */
        String dependsOnScheduleId
) {}
