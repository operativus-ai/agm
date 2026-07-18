--liquibase formatted sql

--changeset agm:078-workflow-steps-on-reject runOnChange:false
--comment: REQ-DR-6 PR-2 — on_reject policy for CONDITION steps. NULL preserves
--         the existing skip-next-step behavior. Valid values: 'SKIP' (default),
--         'CANCEL' (transitions workflow_run to CANCELLED with reason). ELSE_BRANCH
--         is deferred — needs a separate else_step_id column for linking.
--         See docs/analysis/agm-dynamic-routing.md REQ-DR-6.
ALTER TABLE workflow_steps
    ADD COLUMN IF NOT EXISTS on_reject VARCHAR(16);
