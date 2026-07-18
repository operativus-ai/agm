--liquibase formatted sql

--changeset agm:029-agent-audits-immutability-function splitStatements:false
CREATE OR REPLACE FUNCTION agent_audits_reject_mutation()
RETURNS trigger AS $BODY$
BEGIN
    -- DataRetentionService and AuditErasureHandler set this session-local flag
    -- (SET LOCAL) inside their transactional scope before administrative purge
    -- or GDPR redaction. Everything else is rejected.
    IF current_setting('agm.audit_immutability_bypass', true) = 'true' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        ELSE
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'agent_audits is append-only; % is not permitted (§24.4)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$BODY$ LANGUAGE plpgsql;

--changeset agm:029-agent-audits-immutability-trigger
DROP TRIGGER IF EXISTS trg_agent_audits_immutable ON agent_audits;
CREATE TRIGGER trg_agent_audits_immutable
    BEFORE UPDATE OR DELETE ON agent_audits
    FOR EACH ROW
    EXECUTE FUNCTION agent_audits_reject_mutation();
