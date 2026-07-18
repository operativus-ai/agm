--liquibase formatted sql

--changeset agentmanager:005-a2a-schema runOnChange:false
--comment: A2A networking plane schema. Adds api_keys (Gap 2.3 M2M auth) and a2a_remote_agents (Gap 2.1 discovery).

-- ============================================================
-- Gap 2.3: Machine-to-Machine API Keys for Headless A2A Auth
-- ============================================================
-- Stores pre-shared API keys issued to trusted peer agents or external orchestrators.
-- The `key_hash` column stores a BCrypt hash of the raw key — never the plaintext.
-- The ApiKeyAuthenticationFilter validates inbound X-A2A-Api-Key headers against
-- hashed values here, preventing key exposure in DB breach scenarios.
CREATE TABLE IF NOT EXISTS api_keys (
    id               VARCHAR(255) PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,                 -- Friendly label (e.g., "Agno Peer Prod")
    key_hash         VARCHAR(255) NOT NULL,                 -- BCrypt hash of the raw key
    key_prefix       VARCHAR(16)  NOT NULL,                 -- First 8 chars of raw key for quick lookup
    owner_agent_id   VARCHAR(255),                          -- Optional: bound to a specific remote agent
    org_id           VARCHAR(255),                          -- Multi-tenant attribution
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    scopes           JSONB        DEFAULT '["a2a:task:submit","a2a:cards:read"]',
    issued_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMP,                             -- NULL = non-expiring
    last_used_at     TIMESTAMP,
    revoked_at       TIMESTAMP,
    revoked_reason   TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_api_keys_prefix  ON api_keys (key_prefix);
CREATE INDEX IF NOT EXISTS idx_api_keys_active  ON api_keys (active) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS idx_api_keys_owner   ON api_keys (owner_agent_id);

-- ============================================================
-- Gap 2.1: Remote A2A Peer Agent Registry
-- ============================================================
-- Persists registered external A2A peer agents. The A2ACardResolver loads these
-- at startup and refreshes the in-memory cache. A production deployment adds a JPA
-- repository over this table and replaces the ConcurrentHashMap bootstrap.
CREATE TABLE IF NOT EXISTS a2a_remote_agents (
    id               VARCHAR(255) PRIMARY KEY,
    remote_agent_id  VARCHAR(255) NOT NULL,                 -- ID declared by the remote agent's AgentCard
    base_url         VARCHAR(1024) NOT NULL,                -- Root URL of the remote AGM instance
    alias            VARCHAR(255) NOT NULL UNIQUE,          -- Local friendly alias for routing lookups
    outbound_api_key VARCHAR(255),                          -- Plaintext outbound key sent to the remote
                                                            -- (encrypt at rest in production via column-level encryption)
    data_zone        VARCHAR(255),                          -- Optional sovereignty zone (seeds Gap 3.3)
    security_tier    INTEGER       NOT NULL DEFAULT 1,
    trusted          BOOLEAN       NOT NULL DEFAULT TRUE,
    last_verified_at TIMESTAMP,                             -- When the AgentCard was last successfully fetched
    cached_card      JSONB,                                 -- Stale-cache of the last fetched AgentCard
    registered_by    VARCHAR(255),                          -- User/system that registered this peer
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_a2a_remote_agents_alias    ON a2a_remote_agents (alias);
CREATE INDEX IF NOT EXISTS idx_a2a_remote_agents_trusted  ON a2a_remote_agents (trusted) WHERE trusted = TRUE;

-- ============================================================
-- Gap 2.2: A2A Task Execution Audit Log
-- ============================================================
-- Append-only record of every inbound A2A task lifecycle transition.
-- Enables post-hoc audit of cross-boundary executions without relying on volatile
-- in-memory state in A2ATaskExecutor.
CREATE TABLE IF NOT EXISTS a2a_task_events (
    id               BIGSERIAL    PRIMARY KEY,
    task_id          VARCHAR(255) NOT NULL,                 -- Caller-generated idempotency ID
    run_id           VARCHAR(255),                          -- AGM run ID (null until WORKING)
    target_agent_id  VARCHAR(255) NOT NULL,
    initiating_agent VARCHAR(255),
    session_id       VARCHAR(255),
    trace_id         VARCHAR(255),                          -- OTel Trace ID from Gap 2.3
    status           VARCHAR(50)  NOT NULL,                 -- A2aTaskStatus enum value
    message          TEXT,
    error_detail     TEXT,
    event_ts         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_a2a_task_events_task_id  ON a2a_task_events (task_id);
CREATE INDEX IF NOT EXISTS idx_a2a_task_events_run_id   ON a2a_task_events (run_id);
CREATE INDEX IF NOT EXISTS idx_a2a_task_events_trace_id ON a2a_task_events (trace_id);

-- ============================================================
-- L-3 Fix: Referential integrity for a2a_task_events
-- ============================================================
-- Separate changeset so the FK can be deployed independently if agents table
-- already exists but the A2A tables are being added to an existing schema.

--changeset agentmanager:005-a2a-fk-constraints runOnChange:false
--comment: FK constraint from a2a_task_events.target_agent_id to agents.id. ON DELETE RESTRICT preserves audit trail integrity.

ALTER TABLE a2a_task_events
    ADD CONSTRAINT fk_a2a_task_events_agent
    FOREIGN KEY (target_agent_id)
    REFERENCES agents(id)
    ON DELETE RESTRICT;
