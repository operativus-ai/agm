--liquibase formatted sql
--changeset agm:018-alert-integrations
CREATE TABLE IF NOT EXISTS alert_integrations (
    id           VARCHAR(36)  PRIMARY KEY,
    name         TEXT         NOT NULL,
    type         VARCHAR(50)  NOT NULL,
    endpoint_url TEXT         NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
