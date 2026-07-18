--liquibase formatted sql

--changeset agm:088-org-routing-default-router-cached-strategy runOnChange:false
--comment: DR-FR-1 default-router validation. Caches the team_mode of the configured
--         default_router_agent_id at upsert time so the resolver doesn't need a
--         second AgentRegistry lookup on every dispatch. The runtime re-check in
--         RoutingResolverService is the source of truth — this cache is advisory
--         and may drift if a team's team_mode is altered after the config row was
--         last written. NULL when no default router is configured.
ALTER TABLE org_routing_config
    ADD COLUMN IF NOT EXISTS default_router_cached_strategy VARCHAR(50);
