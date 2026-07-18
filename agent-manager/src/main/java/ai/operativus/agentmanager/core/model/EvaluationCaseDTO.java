package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.EvaluationCase;

import java.time.LocalDateTime;

/**
 * Wire DTO for {@link EvaluationCase}. Surfaces the canonical case fields; future
 * schema additions stay server-side unless explicitly added here.
 */
public record EvaluationCaseDTO(
        String id,
        String suiteId,
        String name,
        String input,
        String expectedOutput,
        String systemPromptOverride,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static EvaluationCaseDTO from(EvaluationCase entity) {
        return new EvaluationCaseDTO(
                entity.getId(),
                entity.getSuiteId(),
                entity.getName(),
                entity.getInput(),
                entity.getExpectedOutput(),
                entity.getSystemPromptOverride(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
