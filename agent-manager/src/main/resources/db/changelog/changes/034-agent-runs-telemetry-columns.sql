--liquibase formatted sql

-- AGM logging §5.13: enrich agent_runs with per-run telemetry aggregated by
-- RunTelemetryAccumulator. All columns default NULL so ADD COLUMN is an
-- instant metadata-only operation on PostgreSQL 11+ (no table rewrite,
-- no ACCESS EXCLUSIVE lock rewrite on large tables).

--changeset agm:034-agent-runs-model
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS model VARCHAR(100);

--changeset agm:034-agent-runs-input-tokens
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS input_tokens BIGINT;

--changeset agm:034-agent-runs-output-tokens
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS output_tokens BIGINT;

--changeset agm:034-agent-runs-reasoning-tokens
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT;

--changeset agm:034-agent-runs-duration-ms
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS duration_ms BIGINT;

--changeset agm:034-agent-runs-total-cost-usd
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS total_cost_usd DECIMAL(16,6);

--changeset agm:034-agent-runs-error-type
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS error_type VARCHAR(50);

--changeset agm:034-agent-runs-error-message
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS error_message TEXT;

--changeset agm:034-agent-runs-safety-risk-score
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS safety_risk_score DECIMAL(4,3);

--changeset agm:034-agent-runs-orchestration-strategy
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS orchestration_strategy VARCHAR(50);
