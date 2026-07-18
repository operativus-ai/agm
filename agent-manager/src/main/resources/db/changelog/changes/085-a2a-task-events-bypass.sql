--liquibase formatted sql

--changeset agm:085-a2a-task-events-bypass splitStatements:false
-- Mirror the agm.audit_immutability_bypass check from agent_audits_reject_mutation
-- (changeset 029) so that the demo-wipe changeset and DataRetentionService can
-- delete a2a_task_events rows when the session flag is set.
CREATE OR REPLACE FUNCTION a2a_task_events_reject_mutation()
RETURNS trigger AS $BODY$
BEGIN
    IF current_setting('agm.audit_immutability_bypass', true) = 'true' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSE
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'a2a_task_events is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$BODY$ LANGUAGE plpgsql;
