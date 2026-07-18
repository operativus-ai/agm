-- liquibase formatted sql

-- changeset agm:042-backfill-schedules-org-id
-- Backfills NULL org_id rows in schedules with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Existing rows
-- become visible to the default tenant only; new rows are stamped with the caller's
-- orgId at create time (ScheduleService.createSchedule). Idempotent: WHERE org_id IS NULL
-- guards against re-running.
--
-- Unlike knowledge_bases, the schedules table has no UNIQUE(name) constraint to replace
-- with UNIQUE(name, org_id) — name uniqueness is not enforced at the DB layer for
-- schedules, so two tenants can already have a "Daily report" schedule without collision.
UPDATE schedules
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;
