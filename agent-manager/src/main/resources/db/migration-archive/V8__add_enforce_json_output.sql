-- V8__add_enforce_json_output.sql
-- Adds enforce_json_output column for Phase B JSON Output Enforcement features

ALTER TABLE agents ADD COLUMN enforce_json_output BOOLEAN DEFAULT FALSE;
