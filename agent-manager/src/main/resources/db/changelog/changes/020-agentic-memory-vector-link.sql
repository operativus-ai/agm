--liquibase formatted sql

--changeset agentmanager:020-agentic-memory-vector-link
--comment: Add vector_id to agentic_memories to link each memory ledger entry to its vector_store record, enabling cascade delete and orphan prevention.

ALTER TABLE agentic_memories ADD COLUMN IF NOT EXISTS vector_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_agentic_memories_vector_id ON agentic_memories(vector_id);
