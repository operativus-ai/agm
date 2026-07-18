package com.operativus.agentmanager.core.model;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: Typed carrier for an undecided HumanReview pending row served by
 * {@code GET /api/v1/approvals/human-review} (REQ-HR-5 list endpoint). Exposes the fields an
 * operator needs to triage and decide a pause — the subject under review, the human-readable
 * reason, the review {@code options} (the requiresConfirmation / requiresUserInput /
 * requiresOutputReview union), and the create/expiry timestamps. Decision fields are omitted:
 * this surface only lists rows still awaiting a decision.
 * State: Immutable value carrier.
 */
public record HumanReviewPendingDTO(
        String id,
        String runId,
        String subjectType,
        String subjectId,
        String reason,
        Map<String, Object> options,
        Instant createdAt,
        Instant expiresAt) {
}
