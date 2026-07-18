-- V10__enterprise_agentic_memory_outbox.sql
-- Implements the robust Transactional Outbox for Agentic Memory LLM Processing

CREATE TABLE agentic_memory_outbox (
    outbox_id UUID PRIMARY KEY,
    memory_id UUID NOT NULL, -- Logical link to the agentic_memories ledger row
    payload TEXT NOT NULL, -- Raw contextual chunk 
    status VARCHAR(50) NOT NULL, -- PENDING, PROCESSING, COMPLETED, FAILED
    error_message TEXT,
    retry_count INT DEFAULT 0,
    locked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_status_locked ON agentic_memory_outbox(status, locked_at);
CREATE INDEX idx_outbox_created ON agentic_memory_outbox(created_at);
