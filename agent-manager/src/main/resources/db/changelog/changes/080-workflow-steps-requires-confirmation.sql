--liquibase formatted sql

--changeset agm:080-workflow-steps-requires-confirmation runOnChange:false
--comment: REQ-DR-6 PR-4 — HITL confirmation gate for CONDITION steps. When true,
--         the dispatcher pauses the run with status=PAUSED and a planned cursor
--         that reflects the resolved on_reject policy. Operator confirms by
--         calling the existing POST /api/v1/workflows/runs/{runId}/resume
--         endpoint; rejects by calling DELETE /api/v1/workflows/runs/{runId}.
--         Disallowed on non-CONDITION steps and disallowed in combination with
--         on_reject=CANCEL (cancellation is destructive — operator should cancel
--         the run directly rather than approve a cancellation).
--         See docs/analysis/agm-dynamic-routing.md REQ-DR-6.
ALTER TABLE workflow_steps
    ADD COLUMN IF NOT EXISTS requires_confirmation BOOLEAN NOT NULL DEFAULT false;
