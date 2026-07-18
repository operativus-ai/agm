package com.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: REQ-DR-6 — policy applied by the workflow CONDITION
 *     dispatcher when an expression evaluates to {@code false}. Stored as a
 *     string in {@code workflow_steps.on_reject} and parsed back via
 *     {@link #fromString} at dispatch time.
 *
 *     <ul>
 *       <li>{@link #SKIP} (default; matches NULL) — skip the next step and
 *           continue with the rest of the workflow. Preserves the pre-DR-6
 *           CONDITION dispatcher behavior.</li>
 *       <li>{@link #CANCEL} — transition the workflow_run to
 *           {@code RunStatus.CANCELLED} with reason
 *           {@code CONDITION_REJECT_POLICY}. No further steps run.</li>
 *       <li>{@link #ELSE_BRANCH} (PR-3) — jump the dispatcher cursor to
 *           {@code workflow_steps.else_step_id} and continue from there.
 *           Mirrors ROUTER's branch-and-continue semantics for the false
 *           path. Operators get a true if/else dispatch without needing
 *           the multi-choice ROUTER step type.</li>
 *     </ul>
 *
 * State: Stateless (Enum)
 */
public enum OnRejectPolicy {
    SKIP, CANCEL, ELSE_BRANCH;

    /** Returns the enum constant for the given string, case-insensitive; null / unknown → {@link #SKIP}. */
    public static OnRejectPolicy fromString(String value) {
        if (value == null) return SKIP;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SKIP;
        }
    }
}
