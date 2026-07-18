-- V9__agentic_memory_parity.sql
-- Implements the 2026 Agentic Memory Parity schemas avoiding framework-specific keywords

-- 1. Agentic Memories Table
CREATE TABLE agentic_memories (
    memory_id UUID PRIMARY KEY,
    memory TEXT NOT NULL,
    topics JSONB,
    input TEXT,
    user_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    team_id VARCHAR(255),
    memory_tier VARCHAR(50) NOT NULL, -- Enum: USER_PROFILE, USER_MEMORY, SESSION_CONTEXT, ENTITY_MEMORY, LEARNED_KNOWLEDGE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agentic_memory_user ON agentic_memories(user_id);
CREATE INDEX idx_agentic_memory_tier ON agentic_memories(user_id, memory_tier);

-- 2. Cultural Knowledge Table
CREATE TABLE cultural_knowledge (
    knowledge_id UUID PRIMARY KEY,
    knowledge TEXT NOT NULL,
    topics JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cultural_knowledge_topic ON cultural_knowledge USING GIN (topics);
