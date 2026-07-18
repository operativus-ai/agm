--liquibase formatted sql
--changeset agm:056-cultural-knowledge-org-id

ALTER TABLE cultural_knowledge
    ADD COLUMN org_id VARCHAR(255);

CREATE INDEX idx_cultural_knowledge_org_id ON cultural_knowledge (org_id);
