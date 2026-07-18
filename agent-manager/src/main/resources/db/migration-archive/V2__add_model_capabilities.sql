-- V2__add_model_capabilities.sql
-- Add capabilities to explicitly support robust backend routing and UI filtering

ALTER TABLE models ADD COLUMN supports_tools BOOLEAN DEFAULT TRUE;
ALTER TABLE models ADD COLUMN supports_vision BOOLEAN DEFAULT FALSE;
ALTER TABLE models ADD COLUMN supports_system_instructions BOOLEAN DEFAULT TRUE;
