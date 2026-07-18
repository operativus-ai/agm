package com.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: REQ-HR-1 — what the unified-HumanReview machinery does
 *     when the approval-collection path itself throws (e.g. SSE delivery fails,
 *     audit row write fails, JSON serialization fails). Distinct from the
 *     decided-outcome paths in {@link OnRejectPolicy} (covers "operator rejected")
 *     and {@link OnTimeoutPolicy} (covers "operator never decided"). This enum
 *     covers "the system couldn't even ASK the operator".
 *
 *     <ul>
 *       <li>{@link #CANCEL} (default; matches NULL) — abort the run on any
 *           HumanReview machinery failure. Safe-by-default: don't proceed if
 *           the approval can't be recorded.</li>
 *       <li>{@link #RETRY} — re-attempt the approval collection. Operator must
 *           configure a bounded retry count via separate machinery (out of
 *           scope for this enum; mirrors Spring Retry semantics).</li>
 *       <li>{@link #CONTINUE} — proceed as if approved. Useful for non-critical
 *           confirmation paths where operator availability is best-effort.</li>
 *     </ul>
 *
 * State: Stateless (Enum)
 */
public enum OnErrorPolicy {
    CANCEL, RETRY, CONTINUE;

    /** Returns the enum constant for the given string, case-insensitive; null / unknown → {@link #CANCEL}. */
    public static OnErrorPolicy fromString(String value) {
        if (value == null) return CANCEL;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CANCEL;
        }
    }
}
