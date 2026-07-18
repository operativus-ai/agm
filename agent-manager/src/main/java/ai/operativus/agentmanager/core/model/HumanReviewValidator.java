package ai.operativus.agentmanager.core.model;

import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;

/**
 * Domain Responsibility: REQ-HR-1 — combo validation for {@link HumanReview}
 *     configurations. Called by service-layer create/update paths
 *     (PR-3..5 wiring) BEFORE the JSONB is persisted, so the dispatcher never
 *     has to handle malformed combos at runtime.
 *
 *     <p>Validates structural integrity ONLY — does not check that referenced
 *     {@code elseStepId} resolves to a step in the same workflow (that's a
 *     dispatcher-level concern handled by existing
 *     {@code WorkflowService.validateElseStepId}). This validator is pure-
 *     compute, no repo access.
 *
 * State: Stateless (utility class — all static methods).
 */
public final class HumanReviewValidator {

    private HumanReviewValidator() {}

    /**
     * Validate the structural integrity of a {@link HumanReview}. Null is
     * always valid (means "no HumanReview attached"). A non-null instance
     * must satisfy:
     *
     * <ul>
     *   <li>At most one of {@code requiresConfirmation}, {@code requiresUserInput},
     *       {@code requiresOutputReview} may be true. The three modes are
     *       mutually exclusive — they reflect different pause-points in the
     *       lifecycle (before-yes/no, before-with-input, after-with-edit) that
     *       wouldn't compose meaningfully.</li>
     *   <li>{@code onReject=ELSE_BRANCH} requires non-blank {@code elseStepId}.
     *       Mirrors the existing {@code WorkflowService.validateElseStepId}
     *       combo rule.</li>
     *   <li>{@code elseStepId} non-blank is only valid with {@code onReject=ELSE_BRANCH}.</li>
     *   <li>{@code timeoutSeconds} must be positive when non-null. Zero or
     *       negative would either short-circuit instantly or cause the poller
     *       to misbehave.</li>
     *   <li>{@code onReject=CANCEL} is incompatible with any active pause gate
     *       ({@link HumanReview#isPauseActive()} true). Mirrors the legacy
     *       {@code WorkflowService.validateRequiresConfirmation} constraint
     *       (REQ-DR-6 PR-4): an "approved cancel" is semantically just a
     *       cancel, and the dispatcher cursor logic falls through to SKIP
     *       rather than CANCEL on the approve path — producing surprising
     *       behavior. Cancel the run directly instead.</li>
     * </ul>
     *
     * @throws IllegalArgumentException with a human-readable reason on the
     *     first violation encountered. The message is wired through to the
     *     API surface (typically 400 BAD_REQUEST).
     */
    public static void validate(HumanReview hr) {
        if (hr == null) return;

        int activeRequires = 0;
        if (Boolean.TRUE.equals(hr.requiresConfirmation())) activeRequires++;
        if (Boolean.TRUE.equals(hr.requiresUserInput())) activeRequires++;
        if (Boolean.TRUE.equals(hr.requiresOutputReview())) activeRequires++;
        if (activeRequires > 1) {
            throw new IllegalArgumentException(
                    "HumanReview: at most one of requiresConfirmation, requiresUserInput, "
                            + "requiresOutputReview may be true (mutually exclusive pause modes)");
        }

        OnRejectPolicy policy = hr.onReject();
        boolean hasElseStepId = hr.elseStepId() != null && !hr.elseStepId().isBlank();
        if (policy == OnRejectPolicy.ELSE_BRANCH && !hasElseStepId) {
            throw new IllegalArgumentException(
                    "HumanReview: onReject=ELSE_BRANCH requires non-blank elseStepId");
        }
        if (policy != OnRejectPolicy.ELSE_BRANCH && hasElseStepId) {
            throw new IllegalArgumentException(
                    "HumanReview: elseStepId is only valid when onReject=ELSE_BRANCH");
        }

        Long timeout = hr.timeoutSeconds();
        if (timeout != null && timeout <= 0L) {
            throw new IllegalArgumentException(
                    "HumanReview: timeoutSeconds must be positive when set (got " + timeout + ")");
        }

        if (policy == OnRejectPolicy.CANCEL && hr.isPauseActive()) {
            throw new IllegalArgumentException(
                    "HumanReview: onReject=CANCEL is not allowed with an active pause gate "
                            + "(requiresConfirmation/requiresUserInput/requiresOutputReview) — "
                            + "cancel the run directly instead");
        }
    }
}
