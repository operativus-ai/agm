--liquibase formatted sql

--changeset agm:028-user-org-id
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_users_org_id ON users(org_id);
