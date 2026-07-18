--liquibase formatted sql
--changeset agm:105-extensions-org-id

-- Tenant-scope the extensions registry (#1132). Pre-fix, `extensions` had no org_id, so
-- McpConnectionPool.getAllToolCallbacks() was global and one org's MCP tools were visible to
-- every tenant's agents. Bare org_id string (no FK to an organizations table — matches the
-- repo-wide convention, cf. 058-workflow-runs-org-id). Legacy rows default to the system org
-- so the bootstrap/system tenant's agents keep seeing pre-existing extensions.
ALTER TABLE extensions
    ADD COLUMN org_id VARCHAR(255);

UPDATE extensions
SET org_id = 'DEFAULT_SYSTEM_ORG'
WHERE org_id IS NULL;

ALTER TABLE extensions
    ALTER COLUMN org_id SET NOT NULL;

CREATE INDEX idx_extensions_org_id ON extensions (org_id);
CREATE INDEX idx_extensions_type_org_id_active ON extensions (type, org_id, active);

--rollback DROP INDEX IF EXISTS idx_extensions_type_org_id_active;
--rollback DROP INDEX IF EXISTS idx_extensions_org_id;
--rollback ALTER TABLE extensions DROP COLUMN org_id;
