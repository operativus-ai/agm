--liquibase formatted sql

--changeset agm:087-provider-credentials-id-varchar runOnChange:true
-- Fix-up for 086. The original migration declared id UUID, but the JPA entity
-- ProviderCredential.id is a String (mapped to VARCHAR by Hibernate's default
-- schema validation). The mismatch surfaced at boot as
-- SchemaManagementException: wrong column type ... found [uuid (Types#OTHER)],
-- but expecting [varchar(255) (Types#VARCHAR)]. ALTER ... USING id::text
-- preserves any rows already inserted under the UUID column type.
ALTER TABLE provider_credentials
    ALTER COLUMN id TYPE VARCHAR(255) USING id::text;
