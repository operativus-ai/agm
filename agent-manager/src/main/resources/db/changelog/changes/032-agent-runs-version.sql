-- liquibase formatted sql

-- changeset agentmanager:032-agent-runs-version
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
