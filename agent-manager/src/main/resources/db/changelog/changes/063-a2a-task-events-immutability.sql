--liquibase formatted sql

--changeset agm:063-a2a-task-events-immutability-function splitStatements:false
CREATE OR REPLACE FUNCTION a2a_task_events_reject_mutation()
RETURNS trigger AS $BODY$
BEGIN
    -- a2a_task_events is the A2A counterpart to agent_audits — every lifecycle
    -- transition (SUBMITTED → WORKING → COMPLETED/FAILED/...) appends a new row
    -- via A2aTaskEventRepository.save(). Production code never UPDATEs or
    -- DELETEs from this table; the JpaRepository exposes only finders + save.
    --
    -- No bypass flag today: there is no §24.4-style legitimate redaction or
    -- retention path for these rows yet. Add a `current_setting(...)` bypass
    -- branch (mirroring agent_audits_reject_mutation in changeset 029) when
    -- such a path lands. Keep the trigger function name stable when that
    -- happens so the test class qualifier doesn't drift.
    RAISE EXCEPTION 'a2a_task_events is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$BODY$ LANGUAGE plpgsql;

--changeset agm:063-a2a-task-events-immutability-trigger
DROP TRIGGER IF EXISTS trg_a2a_task_events_immutable ON a2a_task_events;
CREATE TRIGGER trg_a2a_task_events_immutable
    BEFORE UPDATE OR DELETE ON a2a_task_events
    FOR EACH ROW
    EXECUTE FUNCTION a2a_task_events_reject_mutation();
