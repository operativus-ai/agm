--liquibase formatted sql

--changeset agm:074-create-skill-allowed-tools-table runOnChange:false
--comment: REQ-DR-3 Skills system (S1 PR-1a). The allowed_tools[] collection for a
--         skill — each row is a tool name (string) referencing
--         ToolConfig.globalToolProvider. INCLUDES semantics: skills filter the
--         global tool catalog rather than carrying tool definitions. Naming-regex
--         CHECK constraint enforces lowercase-snake-case; runtime tools (Composio)
--         must follow the composio_<slug> convention so post-write tool deletions
--         can be re-validated by a scheduler (gap K). ON DELETE CASCADE so dropping
--         a skill removes its tool bindings (gap E).
CREATE TABLE IF NOT EXISTS skill_allowed_tools (
    skill_id    VARCHAR(255) NOT NULL,
    tool_name   VARCHAR(255) NOT NULL,
    PRIMARY KEY (skill_id, tool_name),
    FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE,
    CONSTRAINT skill_allowed_tools_name_format CHECK (tool_name ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX IF NOT EXISTS idx_skill_allowed_tools_skill
    ON skill_allowed_tools (skill_id);
