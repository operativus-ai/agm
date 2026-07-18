--liquibase formatted sql

--changeset agm:076-add-agent-member-resolver-type runOnChange:false
--comment: REQ-DR-2 Callable Member Composition (PR-3a). Adds member_resolver_type
--         to agents. Defaults to 'STATIC' so every existing team row preserves its
--         current static members behavior — the resolver SPI fires only when an
--         operator flips a team to ORG_TIER, FEATURE_FLAG, or another non-STATIC
--         strategy. See docs/analysis/agm-dynamic-routing.md REQ-DR-2 and
--         agno-reference.md §1.3.
ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS member_resolver_type VARCHAR(64) NOT NULL DEFAULT 'STATIC';

-- Optional read-side index: most queries filter by org_id + is_team first, so a
-- selective index on member_resolver_type alone would mostly miss. Keep the
-- column unindexed in v1; revisit when a query pattern actually depends on it.
