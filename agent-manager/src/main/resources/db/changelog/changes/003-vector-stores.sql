--liquibase formatted sql

--changeset agentmanager:003-vector-stores runOnChange:true
--comment: pgvector-dependent tables for Spring AI vector store and semantic response caching.

-- ============================================================
-- Spring AI Vector Store (with Hybrid Search tsvector)
-- ============================================================
CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding VECTOR(768),
    content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx ON vector_store USING HNSW (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS vector_store_tsv_idx ON vector_store USING GIN(content_tsv);

-- ============================================================
-- Semantic Response Cache (isolated from document vectors)
-- ============================================================
CREATE TABLE IF NOT EXISTS vector_cache (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding VECTOR(768)
);

CREATE INDEX IF NOT EXISTS vector_cache_embedding_idx ON vector_cache USING HNSW (embedding vector_cosine_ops);
