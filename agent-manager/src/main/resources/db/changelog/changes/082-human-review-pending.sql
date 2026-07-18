--liquibase formatted sql

--changeset agm:082-human-review-pending runOnChange:false
--comment: REQ-HR-2 (PR-2 of the HumanReview unification plan). Adds the
--         human_review_pending table that backs the unified pause/decide
--         mechanism. Rows live from pauseFor() until decide() / timeout
--         poller settles them. Subject-type discriminator (WORKFLOW_STEP /
--         AGENT_TOOL_CALL / TEAM_MEMBER_DISPATCH) drives the resume-handler
--         dispatch in HumanReviewService.
--         See docs/analysis/agm-human-review-unification.md REQ-HR-2.
CREATE TABLE IF NOT EXISTS human_review_pending (
    id              VARCHAR(64)  PRIMARY KEY,
    run_id          VARCHAR(255) NOT NULL,
    subject_type    VARCHAR(32)  NOT NULL,
    subject_id      VARCHAR(255) NOT NULL,
    reason          TEXT,
    options         JSONB,
    org_id          VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    expires_at      TIMESTAMP,
    decided_at      TIMESTAMP,
    decision        VARCHAR(32),
    decided_by      VARCHAR(255),
    payload         JSONB
);

-- Run-level lookup: every workflow/agent run pause queries by run_id.
CREATE INDEX IF NOT EXISTS idx_human_review_pending_run_id
    ON human_review_pending (run_id);

-- Tenant scoping: admin-list-pending queries filter on org_id.
CREATE INDEX IF NOT EXISTS idx_human_review_pending_org_id
    ON human_review_pending (org_id);

-- Poller hot path: scan only undecided rows with a non-null expires_at that's
-- past now(). Partial index keeps the scan cheap as decided rows accumulate.
CREATE INDEX IF NOT EXISTS idx_human_review_pending_expires_open
    ON human_review_pending (expires_at)
    WHERE decision IS NULL AND expires_at IS NOT NULL;
