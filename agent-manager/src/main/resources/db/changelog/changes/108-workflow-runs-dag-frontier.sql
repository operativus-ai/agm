--liquibase formatted sql
--changeset agm:108-workflow-runs-dag-frontier

-- DAG frontier-aware resume (REQ-DR-5, DAG-3c). When a DAG run pauses at a HITL gate (an AGENT
-- tool approval / human review, or a ROUTER route selection), the frontier scheduler snapshots the
-- non-derivable bits of its in-flight state — pending node ids, per-node live-token/in-degree
-- counters, LOOP iteration/attempt/loopInput counters, and the paused node ids + pause kind — into
-- this column. Resume rehydrates completed-node outputs from workflow_node_runs (the source of
-- truth) and re-enters the exact graph from the paused frontier. Nullable: existing rows and every
-- flat-engine run leave it null (the flat resume path is unaffected). Bare column on the parent
-- workflow_runs row; tenancy already flows from workflow_runs.org_id.
ALTER TABLE workflow_runs
    ADD COLUMN dag_frontier JSONB;

--rollback ALTER TABLE workflow_runs DROP COLUMN IF EXISTS dag_frontier;
