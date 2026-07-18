package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.model.enums.JobStatus;

import java.time.LocalDateTime;

public record JobStatusResponse(
        String id,
        String jobType,
        JobStatus status,
        String priority,
        String result,
        String errorMessage,
        int retryCount,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static JobStatusResponse from(com.operativus.agentmanager.core.entity.BackgroundJob job) {
        return new JobStatusResponse(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getPriority(),
                job.getResult(),
                job.getErrorMessage(),
                job.getRetryCount(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
