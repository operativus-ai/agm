--liquibase formatted sql

--changeset agentmanager:026-a2a-key-version runOnChange:false
--comment: §22.6 outbound API key rotation. Adds key_version column to a2a_remote_agents so the scheduled OutboundApiKeyMigrationService can identify rows encrypted with a superseded key version and re-encrypt them with the active version. Existing rows default to version 1 — the legacy single-key wire format.

ALTER TABLE a2a_remote_agents
    ADD COLUMN IF NOT EXISTS key_version INTEGER NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_a2a_remote_agents_key_version
    ON a2a_remote_agents (key_version);
