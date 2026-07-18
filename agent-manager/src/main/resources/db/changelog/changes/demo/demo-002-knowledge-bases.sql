--liquibase formatted sql

--changeset agentmanager:demo-002-knowledge-bases context:"demo" runOnChange:true
--comment: Demo knowledge bases — two per org. Document rows reference these KBs.

INSERT INTO knowledge_bases (id, name, description, org_id, owner_id) VALUES
    ('d0000002-0000-0000-0000-000000000001', 'ACME Product Docs',    'Internal documentation for ACME product catalog.', 'DEMO_ACME',   'd0000001-0000-0000-0000-000000000001'),
    ('d0000002-0000-0000-0000-000000000002', 'ACME Compliance',      'Regulatory and compliance reference materials.',   'DEMO_ACME',   'd0000001-0000-0000-0000-000000000001'),
    ('d0000002-0000-0000-0000-000000000003', 'GLOBEX Support Wiki',  'Customer support runbooks for GLOBEX agents.',     'DEMO_GLOBEX', 'd0000001-0000-0000-0000-000000000004')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    org_id = EXCLUDED.org_id,
    owner_id = EXCLUDED.owner_id,
    updated_at = CURRENT_TIMESTAMP;

--changeset agentmanager:demo-002-knowledge-contents context:"demo" runOnChange:true
--comment: Demo knowledge documents — small set per KB so the UI shows content.

INSERT INTO knowledge_contents (id, name, description, content_type, uri, content_hash, size, status, knowledge_base_id, created_at) VALUES
    ('d0000002-0001-0000-0000-000000000101', 'Product Catalog Overview',  'High-level summary of ACME products.',        'TEXT', 'demo://acme/products.md',           'demo-hash-001', 1024, 'COMPLETED', 'd0000002-0000-0000-0000-000000000001', NOW() - INTERVAL '14 days'),
    ('d0000002-0001-0000-0000-000000000102', 'Pricing Tiers 2026',        'Current pricing tiers and discount policy.',   'TEXT', 'demo://acme/pricing.md',            'demo-hash-002',  512, 'COMPLETED', 'd0000002-0000-0000-0000-000000000001', NOW() - INTERVAL '10 days'),
    ('d0000002-0001-0000-0000-000000000103', 'Return Policy',             'SLA and return policy reference.',             'TEXT', 'demo://acme/returns.md',            'demo-hash-003',  384, 'COMPLETED', 'd0000002-0000-0000-0000-000000000001', NOW() - INTERVAL '7 days'),
    ('d0000002-0001-0000-0000-000000000201', 'GDPR Quick Reference',      'GDPR article summary for engineers.',          'TEXT', 'demo://acme/gdpr.md',               'demo-hash-101', 2048, 'COMPLETED', 'd0000002-0000-0000-0000-000000000002', NOW() - INTERVAL '21 days'),
    ('d0000002-0001-0000-0000-000000000202', 'SOC 2 Control Checklist',   'SOC 2 control mapping for audit prep.',        'TEXT', 'demo://acme/soc2.md',               'demo-hash-102', 1536, 'COMPLETED', 'd0000002-0000-0000-0000-000000000002', NOW() - INTERVAL '5 days'),
    ('d0000002-0001-0000-0000-000000000301', 'Tier-1 Support Runbook',    'Triage steps for Tier-1 support agents.',      'TEXT', 'demo://globex/tier1.md',            'demo-hash-201', 3072, 'COMPLETED', 'd0000002-0000-0000-0000-000000000003', NOW() - INTERVAL '30 days'),
    ('d0000002-0001-0000-0000-000000000302', 'Escalation Matrix',         'When to escalate to engineering.',             'TEXT', 'demo://globex/escalation.md',       'demo-hash-202',  768, 'COMPLETED', 'd0000002-0000-0000-0000-000000000003', NOW() - INTERVAL '12 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;
