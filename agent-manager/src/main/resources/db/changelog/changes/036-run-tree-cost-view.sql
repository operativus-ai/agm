--liquibase formatted sql

-- AGM logging plan §5.22 — cost rollup via recursive CTE (Gap 17 Option A).
-- Each run owns only its direct LLM cost (total_cost_usd from RunTelemetryAccumulator
-- flush). tree_total_cost_usd is computed at read time by walking parent_run_id
-- transitively. Zero write-path overhead; correct under concurrent/partial child flushes.

--changeset agm:036-run-tree-cost-view splitStatements:false runOnChange:true
CREATE OR REPLACE VIEW vw_run_tree_cost AS
WITH RECURSIVE run_tree AS (
    SELECT id, parent_run_id, total_cost_usd, id AS root_run_id
    FROM agent_runs
    WHERE parent_run_id IS NULL
    UNION ALL
    SELECT ar.id, ar.parent_run_id, ar.total_cost_usd, rt.root_run_id
    FROM agent_runs ar
    JOIN run_tree rt ON ar.parent_run_id = rt.id
)
SELECT
    root_run_id,
    COALESCE(SUM(total_cost_usd), 0) AS tree_total_cost_usd,
    COUNT(*) AS run_count
FROM run_tree
GROUP BY root_run_id;
