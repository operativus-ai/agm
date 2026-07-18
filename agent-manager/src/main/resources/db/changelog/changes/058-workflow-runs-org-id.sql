--liquibase formatted sql
--changeset agm:058-workflow-runs-org-id

ALTER TABLE workflow_runs
    ADD COLUMN org_id VARCHAR(255);

-- Backfill from parent workflow; any orphaned run (workflow deleted) gets the system org.
UPDATE workflow_runs wr
SET org_id = COALESCE(
        (SELECT w.org_id FROM workflows w WHERE w.id = wr.workflow_id),
        'system'
    )
WHERE wr.org_id IS NULL;

ALTER TABLE workflow_runs
    ALTER COLUMN org_id SET NOT NULL;

CREATE INDEX idx_workflow_runs_org_id ON workflow_runs (org_id);
CREATE INDEX idx_workflow_runs_workflow_id_org_id ON workflow_runs (workflow_id, org_id);

--rollback ALTER TABLE workflow_runs DROP COLUMN org_id;
