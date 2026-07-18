package ai.operativus.agentmanager.core.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Domain Responsibility: Wire-format request body for {@code POST /api/v1/evaluations/feedback}.
 *   Carries a 1..5 star rating and an optional free-form comment for an evaluation run.
 *   FE caller is {@code RunDetailsModal.tsx} (via {@code evaluationApi.submitFeedback});
 *   the FE enforces rating ≥ 1 client-side and this record re-validates the bound on
 *   the BE via Bean Validation. {@code comment} may be null/blank — the FE sends
 *   {@code undefined} when the textarea is empty.
 * State: Immutable record.
 */
public record SubmitFeedbackRequest(
        @NotBlank String runId,
        @NotNull @Min(1) @Max(5) Integer rating,
        String comment
) {
}
