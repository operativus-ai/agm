--liquibase formatted sql

--changeset agentmanager:101-pii-policies-org-id
--comment: Tenant-scope the pii_policies dictionary. Pre-change the table was global and the PiiAdminController class-level hasRole('ADMIN') gate (PR #968) let any admin read/poison/delete the policies any tenant's PII anonymization advisors depend on. Adds org_id NOT NULL with a DEFAULT_SYSTEM_ORG backfill for pre-existing rows; replaces the global name-unique constraint with (org_id, name)-unique so distinct tenants can use the same policy names.

-- 1. Add nullable org_id column for the backfill phase.
ALTER TABLE pii_policies ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

-- 2. Backfill existing rows to the system tenant. These are the legacy global policies
--    that any tenant could see pre-change; assigning them to DEFAULT_SYSTEM_ORG keeps
--    them visible only to system-tenant admins. New per-tenant policies use the caller's
--    actual orgId via AgentContextHolder.getOrgId().
UPDATE pii_policies SET org_id = 'DEFAULT_SYSTEM_ORG' WHERE org_id IS NULL;

-- 3. Promote to NOT NULL now that backfill is complete.
ALTER TABLE pii_policies ALTER COLUMN org_id SET NOT NULL;

-- 4. Drop the global name-unique constraint so distinct tenants can use the same policy
--    names (e.g. each tenant defining their own 'CREDIT_CARD' policy). The constraint
--    was created without an explicit name in 001-schema.sql, so Postgres assigned the
--    default name pii_policies_name_key.
ALTER TABLE pii_policies DROP CONSTRAINT IF EXISTS pii_policies_name_key;

-- 5. Add (org_id, name)-unique so name uniqueness is enforced within a tenant.
ALTER TABLE pii_policies ADD CONSTRAINT uq_pii_policies_org_name UNIQUE (org_id, name);

-- 6. Lookup index for the runtime per-org fallback path
--    (PiiPolicyRepository.findByOrgIdAndEnabledTrue, used by PIIAnonymizationAdvisor and
--    StatefulStreamingPIIAdvisor for every agent request whose agent has no explicit
--    policy bindings).
CREATE INDEX IF NOT EXISTS idx_pii_policies_org_enabled ON pii_policies(org_id, enabled);
