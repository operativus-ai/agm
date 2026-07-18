--liquibase formatted sql
--changeset agm:029-alert-integrations-retry
ALTER TABLE alert_integrations
    ADD COLUMN IF NOT EXISTS retry_count       INT          NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_failure_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error        TEXT,
    ADD COLUMN IF NOT EXISTS next_retry_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_payload   TEXT,
    ADD COLUMN IF NOT EXISTS pending_event_id  VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_alert_integrations_retry
    ON alert_integrations (next_retry_at)
    WHERE retry_count > 0 AND pending_payload IS NOT NULL;
