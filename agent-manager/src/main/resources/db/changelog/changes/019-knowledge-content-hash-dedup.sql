--liquibase formatted sql

--changeset agentmanager:019-knowledge-content-hash-dedup
--comment: Per-KB content hash dedup: unique constraint prevents re-uploading the same file to the same knowledge base.

ALTER TABLE knowledge_contents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_knowledge_content_hash_kb
    ON knowledge_contents (content_hash, knowledge_base_id)
    WHERE content_hash IS NOT NULL;
