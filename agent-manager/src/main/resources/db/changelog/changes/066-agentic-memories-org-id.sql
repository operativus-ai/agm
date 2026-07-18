-- M7: Add org_id to agentic_memories for tenant-scoped deletion.
-- The relational ledger had no org_id while the companion vector_store documents
-- gained it in M4. This gap made efficient org-scoped delete impossible and left
-- MemoryService.deleteMemories with a full-table findAll() scan to match vector IDs.
-- Nullable to accommodate legacy rows; new rows populated by MemoryService.addMemory.
--liquibase formatted sql

--changeset agm:066-agentic-memories-org-id
ALTER TABLE agentic_memories ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_agentic_memories_org_id
    ON agentic_memories(org_id);

CREATE INDEX IF NOT EXISTS idx_agentic_memories_user_org
    ON agentic_memories(user_id, org_id);

CREATE INDEX IF NOT EXISTS idx_agentic_memories_vector_id
    ON agentic_memories(vector_id);
