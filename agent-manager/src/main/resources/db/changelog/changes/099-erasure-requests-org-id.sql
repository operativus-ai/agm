--liquibase formatted sql

--changeset agentmanager:099-erasure-requests-org-id
--comment: Bug #50 — add org_id to erasure_requests so GDPR Art. 17 records carry tenant context. Companion to #931 (controller-level cross-org gate). Nullable for the legacy bootstrap window; a future backfill (JOIN users ON erasure_requests.user_id = users.username, copy users.org_id) can populate historical rows, but is not in scope here. Scoped reads via ErasureRequestRepository.findByUserIdAndOrgIdOrderByRequestedAtDesc filter NULL legacy rows out, which is the safe default — a foreign-org admin shouldn't see them either.

ALTER TABLE erasure_requests
    ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_erasure_requests_org_id_user_id
    ON erasure_requests(org_id, user_id);
