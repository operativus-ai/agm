--liquibase formatted sql

--changeset agentmanager:027-a2a-task-events-drop-agent-fk runOnChange:false
--comment: §22.5 cross-peer cancellation notify. Drops fk_a2a_task_events_agent (added in 005) because target_agent_id legitimately holds peer-origin identifiers: inbound /peers/cancel-notify writes a sentinel ("peer-notify-inbound"); PeerCancellationDispatcher writes the remote peer's remoteAgentId. An audit log that refuses to record cross-boundary events is actively harmful — the column stays VARCHAR(255) NOT NULL, application code owns its semantics.

ALTER TABLE a2a_task_events
    DROP CONSTRAINT IF EXISTS fk_a2a_task_events_agent;
