-- liquibase formatted sql

-- changeset agm:043-backfill-workflows-org-id
-- Backfills NULL org_id rows in workflows with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Existing rows
-- become visible to the default tenant only; new rows are stamped with the caller's
-- orgId at create time (WorkflowService.createWorkflow + cloneWorkflow). Idempotent:
-- WHERE org_id IS NULL guards against re-running.
--
-- Like schedules (changeset 042) and unlike knowledge_bases (041), the workflows
-- table has no UNIQUE(name) constraint to replace with UNIQUE(name, org_id) — name
-- uniqueness is not enforced at the DB layer for workflows.
--
-- Child entities (workflow_steps, workflow_runs) are tenant-scoped via parent
-- traversal (workflow_id → workflows.org_id), so they intentionally do NOT receive
-- their own org_id column.
UPDATE workflows
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;
