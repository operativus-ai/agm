--liquibase formatted sql

--changeset agm:098-schedule-runs-agent-run-id runOnChange:false
--comment: Add agent_run_id to schedule_runs. ScheduleExecutionPoller.executeAndPersist
--         dispatches AGENT/TEAM targets via AgentOperations.run(...) which returns the
--         produced agent_run id, but pre-fix the only place it landed was the output
--         jsonb field as `{"run_id": "..."}` — opaque to clients. The new column gives
--         the FE/CLI a structured pointer for navigation. Mirrors the existing
--         workflow_run_id column shape (NULLABLE varchar, no FK — agent_runs.id is a
--         varchar too and the FK isn't worth the cascade-delete coupling on
--         scheduler-driven runs).
ALTER TABLE schedule_runs ADD COLUMN agent_run_id VARCHAR(255);
CREATE INDEX idx_schedule_runs_agent_run_id ON schedule_runs(agent_run_id) WHERE agent_run_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS idx_schedule_runs_agent_run_id; ALTER TABLE schedule_runs DROP COLUMN agent_run_id;
