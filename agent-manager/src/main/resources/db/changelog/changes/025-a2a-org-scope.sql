--liquibase formatted sql

--changeset agentmanager:025-a2a-org-scope runOnChange:false
--comment: §22.7 cross-org peer isolation. Adds org_id to a2a_remote_agents and relaxes the global UNIQUE(alias) constraint to a composite UNIQUE(org_id, alias) so peers are tenant-scoped. Mirrors the pattern from 007-multi-tenancy-org-id.sql.

ALTER TABLE a2a_remote_agents
    ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_a2a_remote_agents_org_id
    ON a2a_remote_agents (org_id);

-- Drop the global UNIQUE(alias) so two orgs can share an alias.
-- Postgres auto-names the constraint a2a_remote_agents_alias_key from the inline UNIQUE declaration in 005.
ALTER TABLE a2a_remote_agents
    DROP CONSTRAINT IF EXISTS a2a_remote_agents_alias_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_a2a_remote_agents_org_alias
    ON a2a_remote_agents (org_id, alias);
