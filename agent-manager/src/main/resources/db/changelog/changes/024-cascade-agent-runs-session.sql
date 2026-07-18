--liquibase formatted sql

-- ============================================================
-- Migration 024: Promote fk_agent_runs_session to ON DELETE CASCADE
-- ============================================================
-- Migration 016 added fk_agent_runs_session without a cascade rule, so
-- SessionService.deleteSession returned 500 whenever the session had any
-- agent_runs rows referencing it. Drop and re-add the constraint with
-- ON DELETE CASCADE so deleting a session naturally cleans up its runs.

--changeset agm:024-cascade-agent-runs-session
ALTER TABLE agent_runs DROP CONSTRAINT IF EXISTS fk_agent_runs_session;

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_session
        FOREIGN KEY (session_id)
        REFERENCES agent_sessions(session_id)
        ON DELETE CASCADE
        NOT VALID;
