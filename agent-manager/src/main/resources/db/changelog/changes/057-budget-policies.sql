--liquibase formatted sql
--changeset agm:057-budget-policies

CREATE TABLE budget_policies (
    id          VARCHAR(255) PRIMARY KEY,
    org_id      VARCHAR(255) NOT NULL,
    agent_id    VARCHAR(255),
    ceiling_usd NUMERIC(18, 6) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_budget_policies_org_id  ON budget_policies (org_id);
CREATE INDEX idx_budget_policies_agent_id ON budget_policies (agent_id) WHERE agent_id IS NOT NULL;
