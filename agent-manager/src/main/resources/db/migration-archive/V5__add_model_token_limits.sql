-- V5__add_model_token_limits.sql

ALTER TABLE models
ADD COLUMN max_context_tokens INTEGER,
ADD COLUMN max_output_tokens INTEGER;
