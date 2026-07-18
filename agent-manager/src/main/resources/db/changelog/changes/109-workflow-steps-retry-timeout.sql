--liquibase formatted sql
--changeset agm:109-workflow-steps-retry-timeout

-- Per-node DAG resilience config (DAG engine). The frontier scheduler can now retry a node whose
-- executor returns failure and bound each attempt with a wall-clock timeout. All three columns are
-- nullable: null = inherit the global default (agm.workflow.dag.default-node-*), which itself
-- defaults to "no retry, no timeout", so every existing step is behaviourally unchanged. Bare
-- columns on workflow_steps; tenancy already flows from the parent workflow's org scoping.
--   retry_max_attempts — total attempts on failure (paused/stop/success are never retried); 1 = none
--   retry_backoff_ms   — fixed delay between attempts
--   timeout_ms         — per-attempt wall-clock budget; 0/null = unbounded
ALTER TABLE workflow_steps
    ADD COLUMN retry_max_attempts INTEGER,
    ADD COLUMN retry_backoff_ms BIGINT,
    ADD COLUMN timeout_ms BIGINT;

--rollback ALTER TABLE workflow_steps DROP COLUMN IF EXISTS retry_max_attempts, DROP COLUMN IF EXISTS retry_backoff_ms, DROP COLUMN IF EXISTS timeout_ms;
