--liquibase formatted sql

--changeset agm:072-create-skills-table runOnChange:false
--comment: REQ-DR-3 Skills system (S1 PR-1a). Skills are org-scoped reusable bundles
--         of (tool-name allow-list + system-prompt-snippet) that admins attach to
--         agents to augment behavior at run time. Tools are NOT owned by the skill
--         (INCLUDES semantics) — allowed_tools is a filter applied to
--         ToolConfig.globalToolProvider at injection time. See
--         docs/analysis/agm-dynamic-routing.md REQ-DR-3 and agno-reference.md §1.4.
CREATE TABLE IF NOT EXISTS skills (
    id                      VARCHAR(255) PRIMARY KEY,
    org_id                  TEXT NOT NULL,
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    system_prompt_snippet   TEXT,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_skills_org_name
    ON skills (org_id, name);

CREATE INDEX IF NOT EXISTS idx_skills_org_active
    ON skills (org_id, active);
