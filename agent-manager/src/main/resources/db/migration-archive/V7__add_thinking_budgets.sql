-- V7__add_thinking_budgets.sql
-- Add thinking_budget_tokens column to models table to support extended reasoning models

ALTER TABLE models
ADD COLUMN thinking_budget_tokens INT;
