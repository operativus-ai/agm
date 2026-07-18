--liquibase formatted sql

--changeset agm:030-system-audits-table splitStatements:false
CREATE TABLE IF NOT EXISTS system_audits (
    id VARCHAR(64) PRIMARY KEY,
    org_id VARCHAR(255),
    username VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(255),
    http_method VARCHAR(10),
    request_path VARCHAR(512),
    response_status INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

--changeset agm:030-system-audits-index-org-created
CREATE INDEX IF NOT EXISTS idx_system_audits_org_created
    ON system_audits (org_id, created_at DESC);

--changeset agm:030-system-audits-index-username
CREATE INDEX IF NOT EXISTS idx_system_audits_username
    ON system_audits (username);

--changeset agm:030-system-audits-index-resource
CREATE INDEX IF NOT EXISTS idx_system_audits_resource
    ON system_audits (resource_type, resource_id);
