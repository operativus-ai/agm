package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.EvaluationResult;

import java.time.LocalDateTime;

/**
 * Wire DTO for {@link EvaluationResult}. Surfaces the canonical per-case
 * scoring fields; future schema additions stay server-side unless explicitly
 * added here.
 */
public record EvaluationResultDTO(
        String id,
        String runId,
        String caseId,
        String actualOutput,
        Double score,
        Boolean isPassing,
        Long latencyMs,
        Integer tokenUsageTotal,
        String errorMessage,
        LocalDateTime createdAt
) {
    public static EvaluationResultDTO from(EvaluationResult entity) {
        return new EvaluationResultDTO(
                entity.getId(),
                entity.getRunId(),
                entity.getCaseId(),
                entity.getActualOutput(),
                entity.getScore(),
                entity.getIsPassing(),
                entity.getLatencyMs(),
                entity.getTokenUsageTotal(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
