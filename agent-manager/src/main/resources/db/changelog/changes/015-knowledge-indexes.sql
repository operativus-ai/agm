--liquibase formatted sql

--changeset agentmanager:015-knowledge-indexes
--comment: Functional index on vector_store metadata sourceId for performant cascade deletes and chunk lookups.

CREATE INDEX IF NOT EXISTS idx_vector_store_source_id ON vector_store ((metadata->>'sourceId'));
