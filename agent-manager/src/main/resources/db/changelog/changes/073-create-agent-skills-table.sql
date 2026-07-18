--liquibase formatted sql

--changeset agm:073-create-agent-skills-table runOnChange:false
--comment: REQ-DR-3 Skills system (S1 PR-1a). Join table binding agents to skills with
--         a priority ordering. Lower priority number = applied earlier (Unix nice
--         convention); SkillInjector iterates in priority ASC, ties broken by
--         created_at ASC. ON DELETE CASCADE on skill_id so dropping a skill cleans
--         up all agent attachments (spec §5 gap E). Composite PK enforces one
--         attachment per (agent, skill); admins update priority via UPDATE.
CREATE TABLE IF NOT EXISTS agent_skills (
    agent_id    VARCHAR(255) NOT NULL,
    skill_id    VARCHAR(255) NOT NULL,
    priority    INT NOT NULL DEFAULT 100,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, skill_id),
    FOREIGN KEY (skill_id) REFERENCES skills (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_skills_agent_priority
    ON agent_skills (agent_id, priority);
