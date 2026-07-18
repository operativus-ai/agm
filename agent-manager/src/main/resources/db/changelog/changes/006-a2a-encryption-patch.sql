--liquibase formatted sql

--changeset agentmanager:006-a2a-encryption-patch runOnChange:false
--comment: L-2 Fix: Widen outbound_api_key from VARCHAR(255) to VARCHAR(500) to accommodate Base64-encoded AES-256-GCM ciphertext (IV + ciphertext + auth tag).

ALTER TABLE a2a_remote_agents
    ALTER COLUMN outbound_api_key TYPE VARCHAR(500);
