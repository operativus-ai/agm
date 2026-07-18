-- liquibase formatted sql

-- changeset agm:007-add-org-id-to-agents
ALTER TABLE agents ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_agents_org_id ON agents(org_id);

-- changeset agm:007-add-org-id-to-knowledge-bases
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_org_id ON knowledge_bases(org_id);

-- changeset agm:007-add-org-id-to-workflows
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_workflows_org_id ON workflows(org_id);

-- changeset agm:007-add-org-id-to-schedules
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_schedules_org_id ON schedules(org_id);

-- changeset agm:007-add-org-id-to-teams
ALTER TABLE teams ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_teams_org_id ON teams(org_id);

-- changeset agm:007-add-org-id-to-approvals
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_approvals_org_id ON approvals(org_id);
