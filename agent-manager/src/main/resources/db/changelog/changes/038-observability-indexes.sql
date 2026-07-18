--liquibase formatted sql

-- AGM observability plan — Phase 1 T006: indexes supporting read-side query patterns
-- introduced alongside the observability HTTP endpoints. Each index is registered as a
-- separate changeset so rollback is independent.
--
-- 038a: idx_workflow_runs_workflow_id_created_at — backs T003's
--       findByWorkflowIdOrderByCreatedAtDesc; without it the derived query does a
--       seq scan on workflow_runs every time a user opens a workflow's run history.
-- 038b: idx_agent_run_events_run_id_id — accelerates SSE live-tail cursor queries
--       (`WHERE run_id = ? AND id > ?`) landing in later Phase 1 tasks (T005/T012)
--       and makes the retention prune scan cheaper.

--changeset agm:038a-idx-workflow-runs-workflow-id-created-at
CREATE INDEX IF NOT EXISTS idx_workflow_runs_workflow_id_created_at
    ON workflow_runs (workflow_id, created_at DESC);
--rollback DROP INDEX IF EXISTS idx_workflow_runs_workflow_id_created_at;

--changeset agm:038b-idx-agent-run-events-run-id-id
CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_id_id
    ON agent_run_events (run_id, id);
--rollback DROP INDEX IF EXISTS idx_agent_run_events_run_id_id;
