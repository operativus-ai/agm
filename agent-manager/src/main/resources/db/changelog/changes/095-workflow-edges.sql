--liquibase formatted sql

--changeset agm:095-workflow-edges runOnChange:false
--comment: REQ-DR-5 DAG engine foundation. Replaces the implicit linear ordering of
--         workflow_steps.step_order with explicit (from, to, condition) edges so the
--         dispatcher can walk a directed acyclic graph instead of a flat list.
--         This PR only lays the schema + repository; the dispatcher rewrite is PR-2.
--
--         Edges live alongside step_order during the transition — existing flat-list
--         workflows continue to dispatch by step_order; only workflows whose edges
--         table has rows get the DAG walker. Migration tool in PR-5 inserts implicit
--         sequential edges for legacy flat workflows.
--
--         condition column is nullable VARCHAR(255). For unconditional edges (sequential
--         next-step) it is NULL. For ROUTER step branches, it holds the branch key
--         (e.g. "approved", "rejected", "default"). For CONDITION step true/false legs,
--         it holds "true" / "false" / "else".
CREATE TABLE IF NOT EXISTS workflow_edges (
    id              VARCHAR(255) PRIMARY KEY,
    workflow_id     VARCHAR(255) NOT NULL,
    from_step_id    VARCHAR(255) NOT NULL,
    to_step_id      VARCHAR(255) NOT NULL,
    condition       VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_workflow_edges_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_from    FOREIGN KEY (from_step_id) REFERENCES workflow_steps(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_to      FOREIGN KEY (to_step_id)   REFERENCES workflow_steps(id) ON DELETE CASCADE
);

-- A (from, to, condition) tuple is unique. Two distinct condition values between the
-- same pair of steps are allowed (e.g. a CONDITION step with true/false legs to two
-- different next steps), but a duplicate condition is not.
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_edges_unique
    ON workflow_edges (workflow_id, from_step_id, to_step_id, COALESCE(condition, ''));

CREATE INDEX IF NOT EXISTS idx_workflow_edges_workflow ON workflow_edges(workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_edges_from     ON workflow_edges(from_step_id);
CREATE INDEX IF NOT EXISTS idx_workflow_edges_to       ON workflow_edges(to_step_id);
