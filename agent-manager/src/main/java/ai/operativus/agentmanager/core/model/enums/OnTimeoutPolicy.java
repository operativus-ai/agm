package ai.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: REQ-HR-1 — what the unified-HumanReview machinery does
 *     when an approval row's {@code timeout_seconds} elapses without a decision.
 *     The {@code HumanReviewService} timeout poller (REQ-HR-2) reads this value
 *     off the pending row and applies the policy.
 *
 *     <ul>
 *       <li>{@link #AUTO_REJECT} (default; matches NULL) — treat the operator
 *           walkaway as a reject. Safe-by-default for AGM's multi-tenant +
 *           audit-trail posture; an approval that times out should NOT silently
 *           let a destructive action proceed. Documented divergence from Agno
 *           (which defaults to AUTO_APPROVE) — see
 *           {@code agm-human-review-unification.md} §5 D4.</li>
 *       <li>{@link #AUTO_APPROVE} — treat the operator walkaway as an approve.
 *           Operator must explicitly opt in per-HumanReview when this is the
 *           desired behavior (e.g. low-risk read-only approvals).</li>
 *       <li>{@link #CANCEL} — terminate the run immediately on timeout,
 *           regardless of which side would have been chosen. Useful when
 *           operator availability is itself the gate (no review = no run).</li>
 *     </ul>
 *
 * State: Stateless (Enum)
 */
public enum OnTimeoutPolicy {
    AUTO_REJECT, AUTO_APPROVE, CANCEL;

    /** Returns the enum constant for the given string, case-insensitive; null / unknown → {@link #AUTO_REJECT}. */
    public static OnTimeoutPolicy fromString(String value) {
        if (value == null) return AUTO_REJECT;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AUTO_REJECT;
        }
    }
}
