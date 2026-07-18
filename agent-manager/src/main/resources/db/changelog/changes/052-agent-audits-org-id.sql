--liquibase formatted sql

-- Fix Path C — denormalize org_id onto agent_audits (perf upgrade for #286).
-- Pairs with system_audits (changeset 030) for schema convergence: both audit
-- tables now carry org_id directly + index (org_id, created_at DESC).
--
-- Why nullable: an agent_audits row whose agent_id no longer matches any
-- agents.id (agent hard-deleted) cannot derive an org_id. The backfill's
-- inner join excludes such rows, leaving org_id NULL. AgentAuditRepository.search
-- filters on a.orgId = :orgId (caller's org never null per service-layer
-- Objects.requireNonNull guard from Fix B / PR #289), so orphan rows are
-- excluded from every tenant — preserving Fix B's behavior.
--
-- Why SET LOCAL agm.audit_immutability_bypass: changeset 029 installed a
-- BEFORE UPDATE OR DELETE trigger that rejects all mutations unless the
-- session-local flag is set. The backfill UPDATE would otherwise be blocked.
-- SET LOCAL is transaction-scoped — it auto-resets on COMMIT and never
-- leaks to runtime production code. Established precedent: DataRetentionService,
-- ComplianceExportService, AuditErasureHandler.
--
-- Backfill scale caveat: a single UPDATE ... FROM hash join is the most
-- efficient single-statement form. For agent_audits tables exceeding
-- ~5M rows, the lock window may exceed deploy tolerance — operators in
-- that situation should run a pre-migration batched-backfill job (separate
-- operational PR) before the deploy. Below ~5M rows the single-statement
-- migration is acceptable.

--changeset agm:052-add-column
ALTER TABLE agent_audits ADD COLUMN org_id VARCHAR(255);

--changeset agm:052-backfill splitStatements:false
SET LOCAL agm.audit_immutability_bypass = 'true';
UPDATE agent_audits aa
   SET org_id = a.org_id
  FROM agents a
 WHERE aa.agent_id = a.id
   AND aa.org_id IS NULL;

--changeset agm:052-index
CREATE INDEX IF NOT EXISTS idx_agent_audits_org_created
    ON agent_audits (org_id, created_at DESC);
