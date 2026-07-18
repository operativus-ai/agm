package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * Domain Responsibility: REQ-HR-5 — wire-format request body for
 *   {@code POST /api/v1/approvals/{id}/decide}. Carries the operator's
 *   decision (approve/reject) plus optional payload context. Single endpoint
 *   replaces three scattered resume paths (legacy
 *   {@code /approvals/{id}/{approve|reject}}, workflow
 *   {@code /runs/{runId}/resume}, router {@code /runs/{runId}/continue}).
 *
 *   <p>{@code decision} accepted values (case-insensitive at the service
 *   boundary): {@code "approve"}, {@code "reject"}. Service maps these to
 *   {@link ai.operativus.agentmanager.core.model.enums.HumanReviewDecision}.
 *
 *   <p>{@code payload} carries optional operator-provided context. Use cases:
 *   <ul>
 *     <li>{@code requiresUserInput} pause type — operator-provided value to
 *         feed forward as the next-step input.</li>
 *     <li>{@code requiresOutputReview} pause type — operator-provided
 *         override of the produced output.</li>
 *     <li>Router HITL — operator-provided choice key (set
 *         {@code payload.choiceKey}).</li>
 *   </ul>
 *
 * State: Stateless (Immutable Record carrier)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HumanReviewDecideRequest(
        @NotBlank @Pattern(regexp = "(?i)approve|reject",
                message = "decision must be 'approve' or 'reject'") String decision,
        Map<String, Object> payload
) {}
