--liquibase formatted sql

-- ============================================================
-- 022: Models table security hardening
--
-- 1. Widen api_key column to VARCHAR(512) to hold AES-256-GCM
--    ciphertext (Base64: IV[12] + ciphertext + AuthTag[16]).
-- 2. Add created_by / updated_by audit columns.
-- 3. Add indexes on provider and model_type (unindexed filter cols).
-- 4. Add FK agents.model_id -> models.id ON DELETE SET NULL.
--    NOT VALID: constraint enforced on new writes immediately;
--    existing rows are not scanned (safe for live data).
-- ============================================================

--changeset agm:022-models-api-key-widen
ALTER TABLE models
    ALTER COLUMN api_key TYPE VARCHAR(512);

--changeset agm:022-models-audit-cols
ALTER TABLE models
    ADD COLUMN IF NOT EXISTS created_by  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_by  VARCHAR(255);

--changeset agm:022-models-indexes
CREATE INDEX IF NOT EXISTS idx_models_provider   ON models(provider);
CREATE INDEX IF NOT EXISTS idx_models_model_type ON models(model_type);

--changeset agm:022-fk-agents-model-id
ALTER TABLE agents
    ADD CONSTRAINT fk_agents_model_id
        FOREIGN KEY (model_id) REFERENCES models(id)
        ON DELETE SET NULL NOT VALID;
