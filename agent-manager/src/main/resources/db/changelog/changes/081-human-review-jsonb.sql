--liquibase formatted sql

--changeset agm:081-human-review-jsonb runOnChange:false
--comment: REQ-HR-1 (PR-1 of the HumanReview unification plan). Adds a shared
--         human_review JSONB column to workflow_steps, agents, and team_members
--         so the same struct shape can be attached at all three subject types.
--         PR-1 is data-shape only — no runtime behavior change; the dispatcher
--         doesn't yet read this column (REQ-HR-3 wires it). Existing per-mechanism
--         columns (workflow_steps.requires_confirmation / on_reject / else_step_id)
--         remain authoritative until the REQ-HR-6 migration window flips the flag.
--         See docs/analysis/agm-human-review-unification.md REQ-HR-1.
ALTER TABLE workflow_steps
    ADD COLUMN IF NOT EXISTS human_review JSONB;

ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS human_review JSONB;

ALTER TABLE team_members
    ADD COLUMN IF NOT EXISTS human_review JSONB;
