--liquibase formatted sql

--changeset agm:079-workflow-steps-else-step-id runOnChange:false
--comment: REQ-DR-6 PR-3 — else_step_id pointer for the ELSE_BRANCH on_reject
--         policy. Nullable; only populated when on_reject='ELSE_BRANCH'.
--         Service-level validation ensures the target step belongs to the
--         same workflow at create-time. No FK constraint to workflow_steps.id
--         to avoid cyclic-delete ordering pain — soft validation at the
--         service layer is sufficient (workflow_steps are owned by a single
--         workflow and deleted together).
--         See docs/analysis/agm-dynamic-routing.md REQ-DR-6.
ALTER TABLE workflow_steps
    ADD COLUMN IF NOT EXISTS else_step_id VARCHAR(255);
