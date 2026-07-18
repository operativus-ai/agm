--liquibase formatted sql

--changeset agm:090-org-routing-classifier-model runOnChange:false
--comment: DR-FR-2 LLM classifier — org-scoped model selection. When NULL the
--         classifier falls back to the system default (agm.routing.classifier
--         .default-model-id property → DEFAULT_SYSTEM_ORG's fast model). FK
--         ON DELETE SET NULL so deleting a referenced model degrades the
--         classifier to system-default rather than breaking the row.
ALTER TABLE org_routing_config
    ADD COLUMN IF NOT EXISTS classifier_model_id VARCHAR(255);

ALTER TABLE org_routing_config
    DROP CONSTRAINT IF EXISTS fk_org_routing_classifier_model;

ALTER TABLE org_routing_config
    ADD CONSTRAINT fk_org_routing_classifier_model
        FOREIGN KEY (classifier_model_id) REFERENCES models(id) ON DELETE SET NULL;
