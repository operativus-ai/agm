-- liquibase formatted sql

-- changeset agm:046-add-org-id-to-alert-rules
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

-- Backfills NULL org_id rows in alert_rules with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Pre-wave-5 rules
-- become readable to the default tenant only; new rules are stamped with the caller's
-- orgId at create time (AlertingService.createRule).
-- Idempotent: WHERE org_id IS NULL guards against re-running.
UPDATE alert_rules
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;

-- changeset agm:046-add-org-id-to-alert-events
ALTER TABLE alert_events ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

-- Backfills NULL org_id on alert_events from the parent alert_rules.org_id (already
-- backfilled above). Orphan events whose rule was deleted fall back to DEFAULT_SYSTEM_ORG.
-- The rule_id FK has no cascade so orphans are theoretically possible.
UPDATE alert_events
   SET org_id = COALESCE(
       (SELECT org_id FROM alert_rules WHERE alert_rules.id = alert_events.rule_id),
       'DEFAULT_SYSTEM_ORG')
 WHERE org_id IS NULL;
