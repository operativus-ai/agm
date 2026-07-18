package com.operativus.agentmanager.core.model.enums;

/**
 * Domain Responsibility: REQ-HR-2 — terminal-state values for a
 *     {@code human_review_pending} row. Set on the row at decision time;
 *     determines the resume path the {@code HumanReviewService} takes.
 *
 *     <ul>
 *       <li>{@link #APPROVE} — operator explicitly approved via /decide.</li>
 *       <li>{@link #REJECT} — operator explicitly rejected via /decide.</li>
 *       <li>{@link #AUTO_APPROVED} — timeout poller fired with
 *           {@code on_timeout=AUTO_APPROVE}.</li>
 *       <li>{@link #AUTO_REJECTED} — timeout poller fired with
 *           {@code on_timeout=AUTO_REJECT}.</li>
 *       <li>{@link #CANCELLED} — the run was cancelled by another path (cancel
 *           endpoint, parent workflow halt) while the pending row was open. The
 *           pending row is settled but no resume fires.</li>
 *     </ul>
 *
 * State: Stateless (Enum)
 */
public enum HumanReviewDecision {
    APPROVE, REJECT, AUTO_APPROVED, AUTO_REJECTED, CANCELLED;

    /** True for decisions that should fire the approve resume path. */
    public boolean isApprove() {
        return this == APPROVE || this == AUTO_APPROVED;
    }

    /** True for decisions that should fire the reject resume path. */
    public boolean isReject() {
        return this == REJECT || this == AUTO_REJECTED;
    }
}
