--liquibase formatted sql

--changeset agentmanager:004-finops-telemetry-rate-update
--comment: Adding reasoning and cached token rates to the FinOps telemetry specification.

ALTER TABLE finops_valuation_rate ADD COLUMN IF NOT EXISTS cached_input_rate_per_k_tokens DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE finops_valuation_rate ADD COLUMN IF NOT EXISTS reasoning_rate_per_k_tokens DOUBLE PRECISION NOT NULL DEFAULT 0.0;
