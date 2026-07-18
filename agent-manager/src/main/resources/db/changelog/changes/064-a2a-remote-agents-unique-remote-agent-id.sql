--liquibase formatted sql

--changeset agm:064-a2a-remote-agents-unique-remote-agent-id runOnChange:false
--comment: §22.5 follow-on. Mirrors changeset 025 (UNIQUE(org_id, alias)) for the
--         remote_agent_id column. Closes the gap pinned by
--         A2aPeerUniquenessRuntimeTest.registerPeer_sameRemoteAgentId_differentAliases_GAP
--         — two peers under different aliases could previously claim the same
--         remote_agent_id, and PeerCancellationDispatcher.findByRemoteAgentId would
--         resolve arbitrarily. UNIQUE composite covers the (NULL, X) edge for legacy
--         pre-changeset-025 rows: Postgres treats NULLs as distinct under UNIQUE so
--         multiple legacy rows with NULL org_id won't trip the constraint.

CREATE UNIQUE INDEX IF NOT EXISTS uq_a2a_remote_agents_org_remote_agent_id
    ON a2a_remote_agents (org_id, remote_agent_id);
