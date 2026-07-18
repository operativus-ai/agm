--liquibase formatted sql

-- AGM logging plan §5.14 — forensic store for ORCHESTRATOR_DECISION events
-- fanned out by OrchestrationDecisionListener off AgentRunEventBus. Inserts
-- are asynchronous (fire-and-forget virtual-thread executor on the listener)
-- so orchestrator dispatch paths stay off the blocking DB round-trip (R-6).

--changeset agm:035-orchestration-decisions-table splitStatements:false
CREATE TABLE IF NOT EXISTS orchestration_decisions (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255),
    strategy VARCHAR(50) NOT NULL,
    decision_type VARCHAR(50) NOT NULL,
    selected_agent_id VARCHAR(255),
    rationale TEXT,
    decision_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset agm:035-orchestration-decisions-index-run
CREATE INDEX IF NOT EXISTS idx_od_run_id
    ON orchestration_decisions (run_id, created_at ASC);

--changeset agm:035-orchestration-decisions-index-org-ts
CREATE INDEX IF NOT EXISTS idx_od_org_ts
    ON orchestration_decisions (org_id, created_at DESC);

--changeset agm:035-orchestration-decisions-index-strategy
CREATE INDEX IF NOT EXISTS idx_od_strategy
    ON orchestration_decisions (strategy, created_at DESC);
