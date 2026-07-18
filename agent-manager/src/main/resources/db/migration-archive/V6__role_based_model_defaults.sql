-- Drop is_default from models table
ALTER TABLE models DROP COLUMN IF EXISTS is_default;

-- Seed default roles into app_settings
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
('DEFAULT_MODEL_ROUTER', 'gpt-4o-mini', 'Default fast model for Agents (Router/Basic)'),
('DEFAULT_MODEL_HEAVY', 'gpt-4o', 'Default intelligent model for Complex Agents'),
('DEFAULT_MODEL_EMBEDDING', 'text-embedding-3-small', 'Default embedding model for vector store ingestion and RAG')
ON CONFLICT (setting_key) DO NOTHING;
