-- liquibase formatted sql

-- changeset agm:011-agent-canary-fields
ALTER TABLE agents ADD COLUMN IF NOT EXISTS canary_percentage INTEGER;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS canary_base_agent_id VARCHAR(255);

-- changeset agm:011-extension-sandbox-fields
ALTER TABLE extensions ADD COLUMN IF NOT EXISTS sandboxed BOOLEAN DEFAULT true;
ALTER TABLE extensions ADD COLUMN IF NOT EXISTS max_timeout_seconds INTEGER DEFAULT 30;
ALTER TABLE extensions ADD COLUMN IF NOT EXISTS allowed_operations VARCHAR(255);
ALTER TABLE extensions ADD COLUMN IF NOT EXISTS marketplace_category VARCHAR(255);
ALTER TABLE extensions ADD COLUMN IF NOT EXISTS verified BOOLEAN DEFAULT false;

-- changeset agm:011-schedule-dependency-fields
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS depends_on_schedule_id VARCHAR(255);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS timezone VARCHAR(50);
