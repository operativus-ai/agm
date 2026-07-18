--liquibase formatted sql

--changeset agentmanager:021-knowledge-owner-erasure
--comment: Add owner_id to knowledge_contents and knowledge_bases for GDPR ownership tracking; add erasure_requests table for durable right-to-erasure lifecycle.

ALTER TABLE knowledge_contents ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_knowledge_contents_owner_id ON knowledge_contents(owner_id);

ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner_id ON knowledge_bases(owner_id);

CREATE TABLE IF NOT EXISTS erasure_requests (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL,
    requested_by    VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    requested_at    TIMESTAMP    NOT NULL DEFAULT now(),
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    summary         JSONB,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_erasure_requests_user_id ON erasure_requests(user_id);
CREATE INDEX IF NOT EXISTS idx_erasure_requests_status  ON erasure_requests(status);
