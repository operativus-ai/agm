--liquibase formatted sql

--changeset agm:061-approvals-payload-hash
-- T036(8) payload-hash tamper detection. ApprovalService computes
-- SHA-256(toolName + ":" + toolArguments) on createApprovalRequest and stores it here;
-- resolveApprovalForOrg re-computes and compares before transitioning the row to
-- APPROVED/REJECTED. A mismatch means tool_name or tool_arguments was mutated between
-- creation and resolve (direct DB edit, malicious admin, ops mistake) — the resolve
-- is rejected and the row stays PENDING. Nullable because pre-061 rows have no hash;
-- the verify step skips when payload_hash IS NULL so legacy approvals remain resolvable.
ALTER TABLE approvals ADD COLUMN payload_hash VARCHAR(64);
--rollback ALTER TABLE approvals DROP COLUMN payload_hash;
