-- liquibase formatted sql

-- changeset agm:041-backfill-knowledge-bases-org-id
-- Backfills NULL org_id rows in knowledge_bases with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Existing rows
-- become readable to the default tenant only; new rows are stamped with the caller's
-- orgId at create time (KnowledgeBaseController + KnowledgeService.findOrCreateByName).
-- Idempotent: WHERE org_id IS NULL guards against re-running.
UPDATE knowledge_bases
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;

-- changeset agm:041-knowledge-bases-name-unique-per-org
-- Drops the global UNIQUE constraint on knowledge_bases.name and replaces it with a
-- (name, org_id) composite. Two tenants must be able to mint a "Default" KB without
-- collision. The original constraint was created in 001-schema.sql as part of the
-- table definition; Postgres auto-names it "knowledge_bases_name_key" — we drop by
-- that auto-name with IF EXISTS so re-running on a fresh DB (where 001 hasn't run)
-- doesn't fail.
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS knowledge_bases_name_key;
ALTER TABLE knowledge_bases ADD CONSTRAINT knowledge_bases_name_org_id_key UNIQUE (name, org_id);
