-- liquibase formatted sql

-- changeset agm:013-finops-risk-tier
ALTER TABLE agents ADD COLUMN IF NOT EXISTS fin_ops_risk_tier VARCHAR(30);

-- changeset agm:013-tool-category-column
ALTER TABLE agents ADD COLUMN IF NOT EXISTS agent_template VARCHAR(50);
