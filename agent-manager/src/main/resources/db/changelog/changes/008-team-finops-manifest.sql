-- liquibase formatted sql

-- changeset agentmanager:008-team-finops-manifest
-- comment: Add FinOps manifest columns to teams table for budget governance persistence

ALTER TABLE teams ADD COLUMN IF NOT EXISTS human_lead VARCHAR(255);
ALTER TABLE teams ADD COLUMN IF NOT EXISTS max_daily_spend DOUBLE PRECISION;
ALTER TABLE teams ADD COLUMN IF NOT EXISTS min_spending_authority DOUBLE PRECISION;
