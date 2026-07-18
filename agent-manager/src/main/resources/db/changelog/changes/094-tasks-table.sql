--liquibase formatted sql

--changeset agm:094-tasks-table runOnChange:false
--comment: REQ-TT-1 backing table for TeamMode.tasks autonomous task-list orchestrator.
--         Tasks die with their parent team-run via FK ON DELETE CASCADE on team_run_id.
--         status stored as VARCHAR(32) + CHECK constraint per the AGM convention used
--         by routing_decisions, human_review_pending, etc., with the Java enum and
--         Enumerated(STRING) on the JPA side.
--         dispatched_at populated by the atomic status CAS in TasksOrchestrator
--         worker loop. Per D5: UPDATE ... WHERE id=? AND status='PENDING' RETURNING id
--         is the dispatch idempotency primitive, observability filled by dispatched_at.
--         dependencies stored as VARCHAR(255)[] — array of task ids that must reach
--         COMPLETED before this task starts; FAILED dep transitions dependents to
--         BLOCKED via worker-loop policy, not DB constraint.
CREATE TABLE IF NOT EXISTS tasks (
    id                  VARCHAR(255) PRIMARY KEY,
    team_run_id         VARCHAR(255) NOT NULL,
    org_id              VARCHAR(255) NOT NULL,
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    assignee_agent_id   VARCHAR(255),
    parent_task_id      VARCHAR(255),
    dependencies        VARCHAR(255)[] NOT NULL DEFAULT '{}',
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    result              TEXT,
    worker_id           VARCHAR(255),
    dispatched_at       TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_tasks_team_run  FOREIGN KEY (team_run_id)    REFERENCES agent_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_parent    FOREIGN KEY (parent_task_id) REFERENCES tasks(id)      ON DELETE SET NULL,
    CONSTRAINT fk_tasks_assignee  FOREIGN KEY (assignee_agent_id) REFERENCES agents(id)  ON DELETE SET NULL,
    CONSTRAINT chk_tasks_status   CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED','BLOCKED'))
);

CREATE INDEX IF NOT EXISTS idx_tasks_team_run_id ON tasks(team_run_id);
CREATE INDEX IF NOT EXISTS idx_tasks_org_status  ON tasks(org_id, status);
CREATE INDEX IF NOT EXISTS idx_tasks_assignee    ON tasks(assignee_agent_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent      ON tasks(parent_task_id);
