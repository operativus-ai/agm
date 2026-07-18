--liquibase formatted sql
--changeset agm:106-workflow-node-runs

-- DAG workflow engine (REQ-DR-5, DAG-3a). One row per node execution in a DAG run — the
-- per-node trace, the fan-in source for downstream JOINs, and the future resume state.
-- Replaces the flat engine's single workflow_runs.current_payload string with a structured,
-- addressable per-node record. Bare ids (no FK) per the repo-wide convention
-- (cf. 058-workflow-runs-org-id, 095-workflow-edges); tenancy flows from the parent
-- workflow_runs row. content is text (the StepOutput content serialized); token_cost/model_id
-- give per-node FinOps. Unique (run_id, node_id, attempt) so a node's Nth attempt is one row.
CREATE TABLE workflow_node_runs (
    id          VARCHAR(255) PRIMARY KEY,
    run_id      VARCHAR(255) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    node_id     VARCHAR(255) NOT NULL,
    node_name   VARCHAR(255),
    kind        VARCHAR(32)  NOT NULL,
    attempt     INTEGER      NOT NULL DEFAULT 1,
    content     TEXT,
    success     BOOLEAN      NOT NULL DEFAULT true,
    error       TEXT,
    paused      BOOLEAN      NOT NULL DEFAULT false,
    pause_kind  VARCHAR(32),
    token_cost  BIGINT,
    model_id    VARCHAR(255),
    started_at  TIMESTAMP,
    ended_at    TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

ALTER TABLE workflow_node_runs
    ADD CONSTRAINT uq_workflow_node_runs_run_node_attempt UNIQUE (run_id, node_id, attempt);

CREATE INDEX idx_workflow_node_runs_run_id ON workflow_node_runs (run_id);

--rollback DROP INDEX IF EXISTS idx_workflow_node_runs_run_id;
--rollback ALTER TABLE workflow_node_runs DROP CONSTRAINT IF EXISTS uq_workflow_node_runs_run_node_attempt;
--rollback DROP TABLE workflow_node_runs;
