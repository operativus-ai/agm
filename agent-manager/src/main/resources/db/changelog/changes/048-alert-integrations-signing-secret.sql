--liquibase formatted sql
--changeset agm:048-alert-integrations-signing-secret
ALTER TABLE alert_integrations
    ADD COLUMN IF NOT EXISTS signing_secret TEXT;
