--liquibase formatted sql
--changeset agm:110-default-embedding-model

-- Turn semantic search ON by giving the vector store a real default embedding model.
-- Before this, no models row had model_type=EMBEDDING and app_settings had no
-- DEFAULT_MODEL_EMBEDDING, so EmbeddingModelFactory elected NoOpEmbeddingModel (zero vectors)
-- and RAG / agentic-memory search returned noise (see docs/analysis/agm-rag-search.md).
--
-- The model row carries NO api_key: AbstractDynamicModelProvider.resolveApiKey falls back to the
-- per-(org, provider) provider_credentials row. provider MUST be the exact string 'OPENAI'
-- because ProviderCredentialRepository.findByOrgIdAndProvider is a case-sensitive match against
-- the credential row (provider='OPENAI' for DEFAULT_SYSTEM_ORG). The DynamicModelProvider strategy
-- lookup uppercases anyway, so 'OPENAI' satisfies both.
--
-- text-embedding-3-small is reduced to 768 dims by OpenAiModelProvider.buildEmbeddingModel (bound
-- to spring.ai.vectorstore.pgvector.dimension) so it fits the 768-dim vector_store column.
--
-- Inert without a credential: if no OPENAI provider_credential exists (e.g. a fresh prod env),
-- buildEmbeddingModel returns null and the factory falls back to auto-config/NoOp — EmbeddingHealth
-- reports DISABLED_NOOP but health stays UP, so this is safe to ship to every environment.
INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision, supports_system_instructions, model_type, created_at, updated_at) VALUES
('text-embedding-3-small', 'OpenAI text-embedding-3-small (768d)', 'OPENAI', 'text-embedding-3-small', FALSE, FALSE, FALSE, 'EMBEDDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    provider = EXCLUDED.provider,
    model_name = EXCLUDED.model_name,
    model_type = EXCLUDED.model_type,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO app_settings (setting_key, setting_value, description, updated_at) VALUES
('DEFAULT_MODEL_EMBEDDING', 'text-embedding-3-small', 'Default embedding model id (models.id) backing the pgvector store for RAG + agentic memory', CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO UPDATE SET
    setting_value = EXCLUDED.setting_value,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

--rollback DELETE FROM app_settings WHERE setting_key = 'DEFAULT_MODEL_EMBEDDING'; DELETE FROM models WHERE id = 'text-embedding-3-small' AND model_type = 'EMBEDDING';
