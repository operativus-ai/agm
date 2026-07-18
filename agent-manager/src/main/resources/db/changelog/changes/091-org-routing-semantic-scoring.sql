--liquibase formatted sql

--changeset agm:091-org-routing-semantic-scoring runOnChange:false
--comment: DR-FR-3 semantic rule classifier toggle. When TRUE, strategy 3 of
--         the universal-dispatch cascade uses SemanticAgentScorer (cosine
--         similarity over agent description embeddings via Spring AI
--         pgvector VectorStore) instead of the legacy substring matcher.
--         FALSE (default) preserves existing behavior for any org that hasn't
--         opted in. The routing vector table itself is created by Spring AI's
--         PgVectorStore.initializeSchema=true on first bean construction.
ALTER TABLE org_routing_config
    ADD COLUMN IF NOT EXISTS semantic_scoring_enabled BOOLEAN NOT NULL DEFAULT FALSE;
