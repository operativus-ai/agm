-- M8: Add org_id to alert_integrations for tenant-scoped CRUD and dispatch.
-- The table had no org_id, making listIntegrations() return rows from all tenants
-- and onAlertFired dispatch to every enabled integration regardless of org.
-- Nullable to accommodate any legacy rows; new rows stamped by AlertIntegrationService.createIntegration.
--liquibase formatted sql

--changeset agm:067-alert-integrations-org-id
ALTER TABLE alert_integrations ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_alert_integrations_org_id
    ON alert_integrations(org_id);

CREATE INDEX IF NOT EXISTS idx_alert_integrations_org_enabled
    ON alert_integrations(org_id, enabled);
