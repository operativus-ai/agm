--liquibase formatted sql

-- F11 + F17 — vector_cache tenant isolation + PII purge.
-- See .claude/reports/audit-advisor-chain-2026-05-04.md F11/F17.
--
-- Two coupled findings on the semantic response cache stored in vector_cache:
--
-- F11 (P0) — VectorStoreCacheAdvisor.getOrder()=5 ran before
-- PIIAnonymizationAdvisor (order=10), so prompts persisted into vector_cache.content
-- carried PII the redactor would have stripped. Embedding API requests for cache
-- lookup also saw raw prompts. Existing rows are PII-bearing; the
-- VectorStoreCacheAdvisor reorder to >10 (companion PR) does not retroactively
-- redact persisted documents.
--
-- F17 (P0) — vector_cache schema has no org_id column and the advisor's
-- SearchRequest carried no FilterExpression; cache hits crossed tenants.
-- Existing rows, written without an org binding, cannot be retroactively
-- partitioned to a tenant.
--
-- Combined fix: TRUNCATE the cache (rows are PII-bearing AND cross-tenant
-- with no recoverable provenance), then add a JSONB-extract index that the
-- companion advisor change uses for org-scoped FilterExpression lookups.
-- The cache rebuilds on the warm path; the FinOps savings counter resumes
-- once tenants begin populating their isolated cache slices.
--
-- Why metadata JSONB and not a dedicated org_id column: Spring AI's
-- PgVectorStore writes a fixed (id, content, metadata, embedding) schema and
-- ignores extra columns. FilterExpression.eq("org_id", x) translates to a
-- JSONB containment query against metadata. The btree index below extracts
-- metadata->>'org_id' as text and gives the planner a usable selectivity
-- signal without changing the writer's contract.

--changeset agm:053-purge-pii-cache
DELETE FROM vector_cache;

--changeset agm:053-org-metadata-index
CREATE INDEX IF NOT EXISTS idx_vector_cache_metadata_org_id
    ON vector_cache ((metadata ->> 'org_id'));
