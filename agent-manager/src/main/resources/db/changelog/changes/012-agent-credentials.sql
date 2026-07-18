-- liquibase formatted sql

-- changeset agm:012-agent-credentials-table
CREATE TABLE IF NOT EXISTS agent_credentials (
    id              VARCHAR(255) PRIMARY KEY,
    agent_id        VARCHAR(255) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    credential_type VARCHAR(50)  NOT NULL,
    provider_name   VARCHAR(255) NOT NULL,
    encrypted_secret TEXT,
    scopes          TEXT,
    token_endpoint  VARCHAR(512),
    client_id       VARCHAR(255),
    expires_at      TIMESTAMP,
    enabled         BOOLEAN DEFAULT true NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- changeset agm:012-agent-credentials-indexes
CREATE INDEX IF NOT EXISTS idx_agent_credentials_agent_id ON agent_credentials(agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_credentials_agent_provider ON agent_credentials(agent_id, provider_name);
