-- Drop the dead agent_tools join table. It is mapped by NO JPA entity — the app reads an
-- agent's tools from the agents.tools JSONB column (AgentEntity.getTools() →
-- AgentDefinition.tools() → AgentClientFactory.resolveTools), never from this table. The table
-- was a leftover from the retired migration-archive/V3__normalize_agent_tools.sql normalization
-- that never shipped; 001-schema.sql created it and the original 002/demo seeds wrote to it,
-- so every seeded agent silently booted tool-less until #1193/#1194/#1196 moved the bindings to
-- agents.tools. With those seeds fixed, no active changeset writes this table anymore.
--
-- Search-verified before drop: no production code maps or queries agent_tools; no inbound FK
-- references it (only its own outbound agent_id → agents FK); the surviving references in
-- 002/051/demo-003/demo-014 are explanatory comments, not writes. Hibernate ddl-auto=validate
-- ignores unmapped tables, so the live rows were pure dead weight.
--liquibase formatted sql

--changeset agm:111-drop-dead-agent-tools-table
DROP TABLE IF EXISTS agent_tools;
