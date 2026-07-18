package ai.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for
 *   {@code POST /api/v1/evaluations/suites/{suiteId}/cases}. Carries the case name and
 *   input text (both required), plus optional expected output and an optional system-prompt
 *   override applied to the agent at evaluation time.
 *
 * <p>NOTE — pre-existing FE/BE contract drift (verified 2026-05-09 during typing
 * promotion): the FE type {@code CreateEvaluationCaseRequest} in
 * {@code shared/types/evaluation.ts} omits {@code name} and adds unrelated fields
 * ({@code scorerType}, {@code threshold}, {@code metadata}, {@code context}). The FE store's
 * {@code addCase} method has zero direct UI callers on {@code main}, so the drift has not
 * been fired in production. Promotion here matches BE handler ground truth; closing the
 * FE/BE gap is a separate follow-up.
 * State: Immutable record.
 */
public record AddCaseRequest(
        @NotBlank String name,
        @NotBlank String input,
        String expectedOutput,
        String systemPromptOverride
) {
}
