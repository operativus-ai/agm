package com.operativus.agentmanager.core.model;

import java.time.Instant;

/**
 * Domain Responsibility: REQ-HR-5 — wire-format response from
 *   {@code POST /api/v1/approvals/{id}/decide}. Carries the settled
 *   pending row's identifier + the resolved decision + decided-by metadata.
 *   The downstream resume (or cancel) action runs asynchronously after the
 *   response is sent — the handler call is decoupled from the HTTP response
 *   so the operator sees an immediate 200 even when the resume is heavyweight.
 *
 * State: Stateless (Immutable Record carrier)
 */
public record HumanReviewDecideResponse(
        String pendingId,
        String runId,
        String subjectType,
        String decision,
        String decidedBy,
        Instant decidedAt
) {}
