--liquibase formatted sql

--changeset agentmanager:demo-001-users context:"demo" runOnChange:true
--comment: Demo users across two tenants (DEMO_ACME, DEMO_GLOBEX). All demo accounts share password 'yamaha69' (same bcrypt hash as the default admin) for simplicity.

INSERT INTO users (id, username, password, email, first_name, last_name, enabled, org_id) VALUES
('d0000001-0000-0000-0000-000000000001', 'demo-admin',   '$2y$05$/wAeG41xjA1o1asu14BE.uG1V1fwPGqta2FeUbmQxZO8PjrgD9SWy', 'demo-admin@operativus.test',   'Demo', 'Admin',   TRUE, 'DEMO_ACME'),
('d0000001-0000-0000-0000-000000000002', 'demo-analyst', '$2y$05$/wAeG41xjA1o1asu14BE.uG1V1fwPGqta2FeUbmQxZO8PjrgD9SWy', 'demo-analyst@operativus.test', 'Demo', 'Analyst', TRUE, 'DEMO_ACME'),
('d0000001-0000-0000-0000-000000000003', 'demo-viewer',  '$2y$05$/wAeG41xjA1o1asu14BE.uG1V1fwPGqta2FeUbmQxZO8PjrgD9SWy', 'demo-viewer@operativus.test',  'Demo', 'Viewer',  TRUE, 'DEMO_ACME'),
('d0000001-0000-0000-0000-000000000004', 'demo-ops',     '$2y$05$/wAeG41xjA1o1asu14BE.uG1V1fwPGqta2FeUbmQxZO8PjrgD9SWy', 'demo-ops@operativus.test',     'Demo', 'Ops',     TRUE, 'DEMO_GLOBEX')
ON CONFLICT (username) DO UPDATE SET
    email = EXCLUDED.email,
    first_name = EXCLUDED.first_name,
    last_name = EXCLUDED.last_name,
    enabled = EXCLUDED.enabled,
    org_id = EXCLUDED.org_id,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO user_roles (user_id, role) VALUES
('d0000001-0000-0000-0000-000000000001', 'ROLE_ADMIN'),
('d0000001-0000-0000-0000-000000000001', 'ROLE_USER'),
('d0000001-0000-0000-0000-000000000002', 'ROLE_USER'),
('d0000001-0000-0000-0000-000000000003', 'ROLE_VIEWER'),
('d0000001-0000-0000-0000-000000000004', 'ROLE_USER')
ON CONFLICT DO NOTHING;
