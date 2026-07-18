package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.entity.EvaluationSuite;

import java.time.LocalDateTime;

/**
 * Wire DTO for {@link EvaluationSuite}. Omits {@code orgId} (tenant identifier
 * is server-only — no need to surface on the response). Replaces direct-entity
 * returns on {@code EvaluationController} so future schema additions don't
 * automatically leak onto the wire.
 */
public record EvaluationSuiteDTO(
        String id,
        String name,
        String description,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static EvaluationSuiteDTO from(EvaluationSuite entity) {
        return new EvaluationSuiteDTO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
