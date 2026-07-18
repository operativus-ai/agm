--liquibase formatted sql

--changeset agm:103-agent-run-events-notify-fn splitStatements:false
-- Live-transport wake-up signal for the SSE event streams (run / agent / org-wide).
-- On every committed INSERT into agent_run_events, fire a Postgres NOTIFY on the
-- 'agent_run_events' channel with the row's org_id as the payload. AgentRunEventNotifier
-- LISTENs on this channel and wakes the matching SSE pump loops, replacing the fixed
-- 500ms poll with event-driven delivery. The pumps still replay from the table by id
-- (sinceId cursor), so a missed/dropped notification only delays delivery to the next
-- heartbeat — correctness never depends on NOTIFY. NOTIFY is transactional (delivered on
-- commit), so listeners only wake once the row is visible.
-- splitStatements:false — the plpgsql body is dollar-quoted and contains semicolons.
CREATE OR REPLACE FUNCTION notify_agent_run_event() RETURNS trigger AS $$
BEGIN
    -- Payload is the org_id (or '' for legacy null-org rows) so listeners can skip
    -- wake-ups for other tenants. Kept tiny — well under the 8000-byte NOTIFY limit.
    PERFORM pg_notify('agent_run_events', COALESCE(NEW.org_id, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--rollback DROP FUNCTION IF EXISTS notify_agent_run_event();

--changeset agm:103-agent-run-events-notify-trigger
CREATE TRIGGER trg_agent_run_events_notify
    AFTER INSERT ON agent_run_events
    FOR EACH ROW EXECUTE FUNCTION notify_agent_run_event();
--rollback DROP TRIGGER IF EXISTS trg_agent_run_events_notify ON agent_run_events;
