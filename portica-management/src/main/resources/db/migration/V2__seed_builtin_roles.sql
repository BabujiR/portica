-- Built-in, organization-scoped roles. Permission names must match
-- com.portica.management.rbac.Permission. Permissions for later phases (proxies, products,
-- AI, MCP ...) are granted now so role semantics stay stable as features land.

INSERT INTO roles (id, code, display_name, description, built_in, created_at, created_by, updated_at, updated_by)
VALUES
    (gen_random_uuid(), 'ORG_ADMIN',     'Organization Administrator',
     'Full control of the organization, including members and roles.', TRUE, now(), 'system', now(), 'system'),
    (gen_random_uuid(), 'API_ADMIN',     'API Administrator',
     'Designs and manages API proxies, shared flows, products, apps, AI and MCP configuration.', TRUE, now(), 'system', now(), 'system'),
    (gen_random_uuid(), 'OPS_ADMIN',     'Operations Administrator',
     'Manages environments and deployments, runs trace sessions and reads analytics and audit logs.', TRUE, now(), 'system', now(), 'system'),
    (gen_random_uuid(), 'BUSINESS_USER', 'Business User',
     'Manages API products, developers and apps; reads analytics.', TRUE, now(), 'system', now(), 'system'),
    (gen_random_uuid(), 'ANALYST',       'Analyst',
     'Read-only access to configuration and analytics.', TRUE, now(), 'system', now(), 'system'),
    (gen_random_uuid(), 'VIEWER',        'Viewer',
     'Read-only access to configuration.', TRUE, now(), 'system', now(), 'system');

-- ORG_ADMIN: everything
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ORG_UPDATE'),
    ('ENV_READ'), ('ENV_CREATE'), ('ENV_UPDATE'), ('ENV_DELETE'),
    ('MEMBER_READ'), ('MEMBER_MANAGE'),
    ('AUDIT_READ'),
    ('PROXY_READ'), ('PROXY_WRITE'), ('PROXY_DEPLOY'),
    ('PRODUCT_READ'), ('PRODUCT_WRITE'),
    ('DEVELOPER_READ'), ('DEVELOPER_WRITE'),
    ('ANALYTICS_READ'), ('TRACE_RUN'),
    ('AI_READ'), ('AI_WRITE'),
    ('MCP_READ'), ('MCP_WRITE')
) AS p(permission)
WHERE r.code = 'ORG_ADMIN';

-- API_ADMIN
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ENV_READ'), ('MEMBER_READ'),
    ('PROXY_READ'), ('PROXY_WRITE'), ('PROXY_DEPLOY'),
    ('PRODUCT_READ'), ('PRODUCT_WRITE'),
    ('DEVELOPER_READ'), ('DEVELOPER_WRITE'),
    ('ANALYTICS_READ'), ('TRACE_RUN'),
    ('AI_READ'), ('AI_WRITE'),
    ('MCP_READ'), ('MCP_WRITE')
) AS p(permission)
WHERE r.code = 'API_ADMIN';

-- OPS_ADMIN
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ENV_READ'), ('ENV_CREATE'), ('ENV_UPDATE'), ('ENV_DELETE'),
    ('MEMBER_READ'), ('AUDIT_READ'),
    ('PROXY_READ'), ('PROXY_DEPLOY'),
    ('ANALYTICS_READ'), ('TRACE_RUN'),
    ('AI_READ'), ('MCP_READ')
) AS p(permission)
WHERE r.code = 'OPS_ADMIN';

-- BUSINESS_USER
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ENV_READ'),
    ('PROXY_READ'),
    ('PRODUCT_READ'), ('PRODUCT_WRITE'),
    ('DEVELOPER_READ'), ('DEVELOPER_WRITE'),
    ('ANALYTICS_READ')
) AS p(permission)
WHERE r.code = 'BUSINESS_USER';

-- ANALYST
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ENV_READ'),
    ('PROXY_READ'), ('PRODUCT_READ'), ('DEVELOPER_READ'),
    ('ANALYTICS_READ'), ('AI_READ'), ('MCP_READ')
) AS p(permission)
WHERE r.code = 'ANALYST';

-- VIEWER
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
CROSS JOIN (VALUES
    ('ORG_READ'), ('ENV_READ'), ('MEMBER_READ'),
    ('PROXY_READ'), ('PRODUCT_READ'), ('DEVELOPER_READ'),
    ('AI_READ'), ('MCP_READ')
) AS p(permission)
WHERE r.code = 'VIEWER';
