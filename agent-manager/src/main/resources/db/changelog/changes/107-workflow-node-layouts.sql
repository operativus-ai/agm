--liquibase formatted sql
--changeset agm:107-workflow-node-layouts

-- DAG editor node layout (REQ-DR-5). Persists the manual canvas position of each step node so a
-- dragged layout survives reload instead of re-running ELK auto-layout every time. One row per
-- (workflow_id, node_id); node_id is the workflow_steps.id. Bare ids (no FK) per the repo-wide
-- convention (cf. 095-workflow-edges, 106-workflow-node-runs); tenancy flows from the parent
-- workflow. Absence of rows = no saved layout (editor falls back to ELK).
CREATE TABLE workflow_node_layouts (
    id          VARCHAR(255)     PRIMARY KEY,
    workflow_id VARCHAR(255)     NOT NULL,
    node_id     VARCHAR(255)     NOT NULL,
    pos_x       DOUBLE PRECISION NOT NULL,
    pos_y       DOUBLE PRECISION NOT NULL,
    updated_at  TIMESTAMP        NOT NULL DEFAULT now()
);

ALTER TABLE workflow_node_layouts
    ADD CONSTRAINT uq_workflow_node_layouts_workflow_node UNIQUE (workflow_id, node_id);

CREATE INDEX idx_workflow_node_layouts_workflow_id ON workflow_node_layouts (workflow_id);

--rollback DROP INDEX IF EXISTS idx_workflow_node_layouts_workflow_id;
--rollback ALTER TABLE workflow_node_layouts DROP CONSTRAINT IF EXISTS uq_workflow_node_layouts_workflow_node;
--rollback DROP TABLE workflow_node_layouts;
