--liquibase formatted sql

--changeset agentmanager:demo-013-vector-store context:"demo" runOnChange:true splitStatements:false
--comment: Seeded pgvector rows so the vector_store table isn't empty. Embeddings are pseudo-random 768-dim vectors derived per row — real semantic search won't match, but the table renders and "vectors present" displays correctly. metadata JSONB tags each row with seed:"demo-v1" + the source knowledge document id.

DELETE FROM vector_store WHERE metadata->>'seed' = 'demo-v1';

-- Helper: generate a 768-dim vector with values seeded by the row index.
-- pgvector accepts the text format '[v1,v2,...,v768]'::vector.
INSERT INTO vector_store (id, content, metadata, embedding)
SELECT
    gen_random_uuid(),
    'Demo chunk ' || gs.i || ' for document ' || d.doc_id || ': ' || d.snippet,
    jsonb_build_object(
        'seed', 'demo-v1',
        'knowledge_base_id', d.kb_id,
        'knowledge_content_id', d.doc_id,
        'chunk_index', gs.i,
        'source', d.snippet
    ),
    -- Construct a 768-dim vector with deterministic per-row values:
    -- value = sin(i * chunk + j) / 10, varying per chunk index and dimension.
    ('[' || string_agg(
        round((sin((gs.i * 17 + dim.n)::numeric) / 10)::numeric, 6)::text,
        ',' ORDER BY dim.n
    ) || ']')::vector
FROM (VALUES
    ('demo-doc-001', 'd0000002-0000-0000-0000-000000000001', 'd0000002-0001-0000-0000-000000000101', 'ACME product catalog and SKU overview'),
    ('demo-doc-002', 'd0000002-0000-0000-0000-000000000001', 'd0000002-0001-0000-0000-000000000102', 'Pricing tiers and discount eligibility'),
    ('demo-doc-003', 'd0000002-0000-0000-0000-000000000001', 'd0000002-0001-0000-0000-000000000103', 'Return policy and SLA reference'),
    ('demo-doc-004', 'd0000002-0000-0000-0000-000000000002', 'd0000002-0001-0000-0000-000000000201', 'GDPR Article 15 to 22 summaries'),
    ('demo-doc-005', 'd0000002-0000-0000-0000-000000000002', 'd0000002-0001-0000-0000-000000000202', 'SOC 2 Trust Service Criteria checklist'),
    ('demo-doc-006', 'd0000002-0000-0000-0000-000000000003', 'd0000002-0001-0000-0000-000000000301', 'Tier-1 support triage runbook'),
    ('demo-doc-007', 'd0000002-0000-0000-0000-000000000003', 'd0000002-0001-0000-0000-000000000302', 'Escalation matrix for engineering handoff')
) AS d(doc_id, kb_id, content_doc_id, snippet)
CROSS JOIN generate_series(1, 3) AS gs(i)
CROSS JOIN generate_series(1, 768) AS dim(n)
GROUP BY d.doc_id, d.kb_id, d.snippet, gs.i;
