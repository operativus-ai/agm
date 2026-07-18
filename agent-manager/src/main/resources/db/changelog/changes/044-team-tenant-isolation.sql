-- liquibase formatted sql

-- changeset agm:044-backfill-teams-org-id
-- Backfills NULL org_id rows in teams with the system default tenant.
-- Mirrors TenantConstants.DEFAULT_SYSTEM_ORG ("DEFAULT_SYSTEM_ORG"). Existing rows
-- become visible to the default tenant only; new rows are stamped with the caller's
-- orgId at create time (TeamService.createTeam). Idempotent: WHERE org_id IS NULL
-- guards against re-running.
--
-- No UNIQUE(name) to replace (unlike knowledge_bases). Same shape as schedules (042)
-- and workflows (043).
--
-- Child entities (team_members, transition_edges, team_tools, team_manifest) are
-- tenant-scoped via parent traversal (team_id → teams.org_id), so they intentionally
-- do NOT receive their own org_id column.
UPDATE teams
   SET org_id = 'DEFAULT_SYSTEM_ORG'
 WHERE org_id IS NULL;
