--liquibase formatted sql

--changeset agent-manager:070-spot-batch-jobs-org-id
--comment: F5 tenant-scope spot_batch_jobs. Table had no org_id; GET /api/v1/schedules/batches
--         was globally visible to any ROLE_ADMIN — admins across tenants all saw the same
--         row set. Adds org_id with backfill to DEFAULT_SYSTEM_ORG so any rows seeded
--         before this change keep showing up under the platform/system tenant.
--         Service layer filters reads by callerOrgId via the new findAllByOrgId method.
ALTER TABLE spot_batch_jobs
    ADD COLUMN IF NOT EXISTS org_id TEXT NOT NULL DEFAULT 'DEFAULT_SYSTEM_ORG';

CREATE INDEX IF NOT EXISTS idx_spot_batch_jobs_org_id
    ON spot_batch_jobs(org_id);
