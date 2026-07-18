--liquibase formatted sql
--changeset agm:050-composio-config

-- DB-backed runtime configuration for the Composio dynamic tool adapter.
-- Pairs with the existing properties-file fallback in application.properties:
-- when both DB and properties hold values, the DB wins (per agmui-tool-support-composio.md §3
-- Edge Case "DB > properties-file"). When DB tables are empty (fresh deploy), the boot-time
-- @Value-bound lists fall back to the properties-file values — preserving zero-config
-- behavior for deployments that haven't migrated to DB-managed config yet.
--
-- Per A20: tier is INTEGER (boxed Integer in JPA — null disallowed at column level, but null
-- handling is forbidden at the application layer; null tier on a row would be a bug). version
-- is the JPA @Version column for optimistic locking on concurrent operator edits.

CREATE TABLE composio_action_config (
    id              VARCHAR(255) PRIMARY KEY,
    action_name     VARCHAR(255) NOT NULL UNIQUE,
    llm_tool_name   VARCHAR(255) NOT NULL,
    tier            INTEGER      NOT NULL CHECK (tier IN (1, 2, 3)),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    version         INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255)
);

CREATE INDEX idx_composio_action_config_enabled ON composio_action_config (enabled) WHERE enabled = TRUE;
CREATE INDEX idx_composio_action_config_tier    ON composio_action_config (tier);

-- Per-org connection IDs. One row per org. Matches the existing
-- application.properties shape `agent.tools.composio.connection-ids.<orgId>=<id>`.
-- ComposioToolCallback.call() reads via Environment.getProperty when DB-fallback returns
-- empty, so this table is additive — does not break properties-file deploys.

CREATE TABLE composio_connection_config (
    id            VARCHAR(255) PRIMARY KEY,
    org_id        VARCHAR(255) NOT NULL UNIQUE,
    connection_id VARCHAR(255) NOT NULL,
    version       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP
);
