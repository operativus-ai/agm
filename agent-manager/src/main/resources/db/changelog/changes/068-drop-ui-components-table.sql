-- Drop the orphan ui_components table left behind by PR #659 (which deleted the
-- ComponentsController + Component{Entity,Repository,DTO}). No remaining writers,
-- no remaining readers, and no FKs referencing it — Hibernate validate is permissive
-- about extra tables so it stayed silently, but cleaning up keeps the schema honest.
--
-- Search-verified before drop: no production code references ui_components or
-- ComponentEntity; no Liquibase changeset prior to this one populates it (the
-- create-time changeset 001 introduced it as a placeholder for an admin-UI
-- preview-builder feature that never shipped).
--liquibase formatted sql

--changeset agm:068-drop-ui-components-table
DROP TABLE IF EXISTS ui_components;
