-- liquibase formatted sql

-- changeset agm:009-add-version-number-to-audits
ALTER TABLE agent_audits ADD COLUMN IF NOT EXISTS version_number INTEGER;

-- changeset agm:009-add-role-operator-and-hierarchy
-- Expand user_roles to support new roles (ROLE_OPERATOR, ROLE_VIEWER)
-- No schema change needed — user_roles is an ElementCollection with VARCHAR role column.
-- The RoleType enum expansion handles this at the application layer.

-- changeset agm:009-add-alert-rules-table
CREATE TABLE IF NOT EXISTS alert_rules (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    metric_name VARCHAR(255) NOT NULL,
    condition VARCHAR(50) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    window_seconds INTEGER NOT NULL DEFAULT 60,
    severity VARCHAR(50) NOT NULL DEFAULT 'WARNING',
    enabled BOOLEAN NOT NULL DEFAULT true,
    notification_channel VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- changeset agm:009-add-alert-events-table
CREATE TABLE IF NOT EXISTS alert_events (
    id VARCHAR(255) PRIMARY KEY,
    rule_id VARCHAR(255) NOT NULL REFERENCES alert_rules(id),
    metric_value DOUBLE PRECISION NOT NULL,
    message TEXT,
    severity VARCHAR(50) NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT false,
    fired_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_alert_events_rule_id ON alert_events(rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_events_fired_at ON alert_events(fired_at);
