package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.EvaluationRun;
import ai.operativus.agentmanager.core.model.enums.RunStatus;

import java.time.LocalDateTime;

/**
 * Wire DTO for {@link EvaluationRun}. Surfaces the canonical run-summary fields;
 * future schema additions stay server-side unless explicitly added here.
 */
public record EvaluationRunDTO(
        String id,
        String suiteId,
        String agentId,
        RunStatus status,
        Integer totalCases,
        Integer passedCases,
        Integer failedCases,
        Double averageScore,
        Long averageLatencyMs,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage,
        LocalDateTime createdAt
) {
    public static EvaluationRunDTO from(EvaluationRun entity) {
        return new EvaluationRunDTO(
                entity.getId(),
                entity.getSuiteId(),
                entity.getAgentId(),
                entity.getStatus(),
                entity.getTotalCases(),
                entity.getPassedCases(),
                entity.getFailedCases(),
                entity.getAverageScore(),
                entity.getAverageLatencyMs(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
