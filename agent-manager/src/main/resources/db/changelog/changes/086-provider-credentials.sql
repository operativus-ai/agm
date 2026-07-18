--liquibase formatted sql

--changeset agm:086-provider-credentials runOnChange:true
-- Per-org provider API key store. One row per (org_id, provider) holds the
-- encrypted default key for that LLM provider. ModelEntity.api_key remains a
-- per-model override; AbstractDynamicModelProvider.resolveApiKey checks the
-- override first, falls through to provider_credentials for the caller's org,
-- then throws if neither is set. Replaces the prior env-var / Spring property
-- fallback chain that resolved keys from .env and OS environment.
CREATE TABLE IF NOT EXISTS provider_credentials (
    id          UUID         PRIMARY KEY,
    org_id      VARCHAR(255) NOT NULL,
    provider    VARCHAR(50)  NOT NULL,
    api_key     VARCHAR(512) NOT NULL,
    label       VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_provider_credentials_org_provider UNIQUE (org_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_provider_credentials_org_id ON provider_credentials(org_id);
