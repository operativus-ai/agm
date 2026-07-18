--liquibase formatted sql

--changeset agm:075-create-org-routing-config-table runOnChange:false
--comment: REQ-DR-1 Universal Dispatch (PR-2a). Per-org configuration for the
--         POST /api/runs entry point: which agent (if any) handles requests
--         that arrive without an explicit agentId. Three resolution strategies
--         compose in priority order:
--           1. default_router_agent_id — a designated team configured per org
--           2. llm_classifier_enabled — LLM picks from active agents in the org
--           3. rule_classifier_enabled — match by tags/description
--         If all strategies miss, fallback_agent_id receives the run.
--         One row per org (uq on org_id). See docs/analysis/agm-dynamic-routing.md
--         REQ-DR-1 and agno-reference.md §1.1.
CREATE TABLE IF NOT EXISTS org_routing_config (
    id                          VARCHAR(255) PRIMARY KEY,
    org_id                      TEXT NOT NULL,
    default_router_agent_id     VARCHAR(255),
    fallback_agent_id           VARCHAR(255),
    llm_classifier_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    rule_classifier_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_org_routing_config_org_id
    ON org_routing_config (org_id);
