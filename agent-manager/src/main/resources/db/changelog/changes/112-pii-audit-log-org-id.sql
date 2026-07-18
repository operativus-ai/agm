-- Changeset 112: tenant-scope the PII audit log so it can be served through a REST endpoint
-- (GET /api/v1/pii-policies/audit-log). The pii_audit_log table (001-schema.sql) predates
-- multi-tenancy (007-multi-tenancy-org-id.sql) and carried no org_id, so the log could not be
-- exposed safely: findAll() would cross tenants and a user-supplied agent_id is IDOR. Add org_id,
-- backfill from the owning agent, and index the org-scoped ordered query. New rows are stamped
-- with org_id by PIIAnonymizationAdvisor / StatefulStreamingPIIAdvisor at write time.

--liquibase formatted sql

--changeset agm:112-pii-audit-log-org-id
ALTER TABLE pii_audit_log ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

-- Backfill from the owning agent (pii_audit_log.agent_id = agents.id, both VARCHAR(255)). Rows with
-- a null/unmatched agent_id stay org_id=NULL and remain invisible to every tenant's scoped query
-- (fail-closed) until regenerated — acceptable for a pre-launch dataset.
UPDATE pii_audit_log p
   SET org_id = a.org_id
  FROM agents a
 WHERE p.agent_id = a.id
   AND p.org_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_pii_audit_org ON pii_audit_log(org_id);
CREATE INDEX IF NOT EXISTS idx_pii_audit_org_created ON pii_audit_log(org_id, created_at DESC);
