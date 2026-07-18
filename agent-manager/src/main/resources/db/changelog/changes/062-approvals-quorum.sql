--liquibase formatted sql

--changeset agm:062-approvals-quorum
-- T036(4) multi-approver quorum. approvers_required is the threshold (1 = current
-- single-approver behaviour, preserved for every existing row via the DEFAULT).
-- approved_by is the JSONB array of resolver IDs that have already voted APPROVED;
-- ApprovalService dedupes against this list (a resolver cannot count twice) and
-- transitions status=PENDING → APPROVED only when array length reaches approvers_required.
-- A single REJECTED still terminates the row immediately — quorum applies to APPROVE only.
ALTER TABLE approvals ADD COLUMN approvers_required INTEGER NOT NULL DEFAULT 1;
ALTER TABLE approvals ADD COLUMN approved_by JSONB NOT NULL DEFAULT '[]'::jsonb;
--rollback ALTER TABLE approvals DROP COLUMN approved_by; ALTER TABLE approvals DROP COLUMN approvers_required;
