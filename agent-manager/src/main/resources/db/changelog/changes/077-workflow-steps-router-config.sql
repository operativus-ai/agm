--liquibase formatted sql

--changeset agm:077-workflow-steps-router-config runOnChange:false
--comment: REQ-DR-4 Workflow Router Step (PR-1). Adds router_config JSONB to
--         workflow_steps so ROUTER step rows can carry structured selector config
--         (selectorType, selectorExpression, choices map). Nullable: non-ROUTER
--         rows leave the column NULL; the dispatcher falls back to AGENT behavior
--         when an action=ROUTER row has a NULL router_config (defensive, matches
--         the StepActionType docstring contract). See
--         docs/analysis/agm-dynamic-routing.md REQ-DR-4.
ALTER TABLE workflow_steps
    ADD COLUMN IF NOT EXISTS router_config JSONB;
