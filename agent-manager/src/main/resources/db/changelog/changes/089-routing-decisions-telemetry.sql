--liquibase formatted sql

--changeset agm:089-routing-decisions-table runOnChange:false
--comment: DR-FR-4 routing-decision telemetry. One row per resolveAgentId
--         invocation (including UNRESOLVED outcomes), recording which
--         strategy fired, what the resolved agent was, optional confidence
--         (LLM classifier only), candidate count, and latency. Append-only
--         via trg_routing_decisions_immutable — mirrors the agent_audits
--         pattern from changeset 029. Legitimate deletes (GDPR erasure,
--         retention purge) set agm.audit_immutability_bypass='true' on the
--         active transaction.
CREATE TABLE IF NOT EXISTS routing_decisions (
    id                  VARCHAR(255) PRIMARY KEY,
    org_id              VARCHAR(255) NOT NULL,
    user_id             VARCHAR(255),
    session_id          VARCHAR(255),
    message_hash        CHAR(64)     NOT NULL,
    message_length      INT,
    resolved_agent_id   VARCHAR(255),
    resolution_status   VARCHAR(32)  NOT NULL,
    strategy_used       VARCHAR(32)  NOT NULL,
    confidence          DECIMAL(4,3),
    latency_ms          INT,
    candidate_count     INT,
    rationale           TEXT,
    trace_id            VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_routing_decisions_org_created
    ON routing_decisions (org_id, created_at);
CREATE INDEX IF NOT EXISTS idx_routing_decisions_strategy
    ON routing_decisions (strategy_used);

--changeset agm:089-routing-decisions-immutability-function splitStatements:false
CREATE OR REPLACE FUNCTION routing_decisions_reject_mutation()
RETURNS trigger AS $BODY$
BEGIN
    IF current_setting('agm.audit_immutability_bypass', true) = 'true' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSE
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'routing_decisions is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$BODY$ LANGUAGE plpgsql;

--changeset agm:089-routing-decisions-immutability-trigger
DROP TRIGGER IF EXISTS trg_routing_decisions_immutable ON routing_decisions;
CREATE TRIGGER trg_routing_decisions_immutable
    BEFORE UPDATE OR DELETE ON routing_decisions
    FOR EACH ROW
    EXECUTE FUNCTION routing_decisions_reject_mutation();
