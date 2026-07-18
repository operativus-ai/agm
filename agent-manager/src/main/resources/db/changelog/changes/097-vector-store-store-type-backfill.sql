--liquibase formatted sql

--changeset agm:097-vector-store-store-type-backfill runOnChange:false
--comment: Backfill the storeType discriminator on existing vector_store rows so the
--         MemoryService and KnowledgeService search filters (which AND
--         metadata->>'storeType' into their predicates) match historical rows. Rows
--         carrying a knowledgeBaseId came from KnowledgeService ingestion; everything
--         else came from MemoryService.addMemory. New writes from both services tag
--         the column themselves; this changeset only patches pre-existing rows.
UPDATE vector_store
   SET metadata = jsonb_set(metadata, '{storeType}', '"KB"'::jsonb, true)
 WHERE metadata->>'storeType' IS NULL
   AND metadata->'knowledgeBaseId' IS NOT NULL;

UPDATE vector_store
   SET metadata = jsonb_set(metadata, '{storeType}', '"MEMORY"'::jsonb, true)
 WHERE metadata->>'storeType' IS NULL;
--rollback UPDATE vector_store SET metadata = metadata - 'storeType' WHERE metadata->>'storeType' IN ('KB','MEMORY');
