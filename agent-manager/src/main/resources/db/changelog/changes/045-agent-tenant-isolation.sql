-- liquibase formatted sql

-- changeset agm:045-backfill-agents-org-id
-- Backfills NULL org_id rows in agents with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Existing rows
-- become readable to the default tenant only; new rows are stamped with the caller's
-- orgId at create time (AgentAdminService.createAgent).
-- Idempotent: WHERE org_id IS NULL guards against re-running.
UPDATE agents
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;
