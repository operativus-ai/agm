--liquibase formatted sql

-- Migration 054: composite index on agent_runs(status, created_at) supporting
-- the stuck-PAUSED sweeper introduced by Tier 2.4 PR 5 (#358) and refined in
-- Tier 2.4 PR 7 (#366).
--
-- The existing single-column idx_agent_runs_status (changeset 016) lets the
-- sweeper find all PAUSED rows but forces the cutoff filter to run in Java
-- memory (RunExecutionManager.expireStuckPausedRuns lines 100-104:
-- findByStatusIn(...).stream().filter(r -> r.getCreatedAt().isBefore(cutoff))).
-- As PAUSED row count grows this becomes a memory + GC hot path on a 1h cron.
--
-- With this composite the sweeper's SQL collapses to a single index range scan
-- on (status='PAUSED', created_at < cutoff), unblocking the companion
-- RunRepository change to push the cutoff filter into SQL.
--
-- Coexists with idx_agent_runs_status (changeset 016): the planner picks the
-- single-column index for status-only predicates and the composite for
-- combined status+created_at predicates. Removing the single-column index is
-- deferred pending audit of all current callers of findByStatus/findByStatusIn.

--changeset agm:054-idx-agent-runs-status-created-at
CREATE INDEX IF NOT EXISTS idx_agent_runs_status_created_at
    ON agent_runs (status, created_at);
--rollback DROP INDEX IF EXISTS idx_agent_runs_status_created_at;
