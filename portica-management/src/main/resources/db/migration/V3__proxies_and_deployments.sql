-- Phase 2: target servers, API proxies with immutable revisions, and deployments.

-- Bumped on every runtime-relevant change in an environment (deploy, undeploy, target server change).
-- Gateways compare it to decide whether to re-fetch the environment's runtime configuration.
ALTER TABLE environments ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- Target servers: named backend endpoints per environment (e.g. "orders-backend"
-- points at orders.dev.internal in dev and orders.prod.internal in prod).
-- ---------------------------------------------------------------------------
CREATE TABLE target_servers (
    id              UUID          PRIMARY KEY,
    environment_id  UUID          NOT NULL REFERENCES environments (id) ON DELETE CASCADE,
    name            VARCHAR(63)   NOT NULL,
    description     VARCHAR(1024),
    host            VARCHAR(255)  NOT NULL,
    port            INTEGER       NOT NULL,
    protocol        VARCHAR(8)    NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      VARCHAR(255),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_target_servers_env_name UNIQUE (environment_id, name),
    CONSTRAINT ck_target_servers_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_target_servers_protocol CHECK (protocol IN ('HTTP', 'HTTPS'))
);

-- ---------------------------------------------------------------------------
-- API proxies and their immutable revisions
-- ---------------------------------------------------------------------------
CREATE TABLE api_proxies (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    latest_revision  INTEGER       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_proxies_org_name UNIQUE (organization_id, name)
);

CREATE TABLE api_proxy_revisions (
    id                  UUID           PRIMARY KEY,
    api_proxy_id        UUID           NOT NULL REFERENCES api_proxies (id) ON DELETE CASCADE,
    revision            INTEGER        NOT NULL,
    description         VARCHAR(1024),
    base_path           VARCHAR(255)   NOT NULL,
    target_url          VARCHAR(2048),
    target_server       VARCHAR(63),
    target_path         VARCHAR(1024)  NOT NULL DEFAULT '',
    connect_timeout_ms  INTEGER        NOT NULL,
    read_timeout_ms     INTEGER        NOT NULL,
    openapi_spec        TEXT,
    created_at          TIMESTAMPTZ    NOT NULL,
    created_by          VARCHAR(255),
    updated_at          TIMESTAMPTZ    NOT NULL,
    updated_by          VARCHAR(255),
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_proxy_revisions UNIQUE (api_proxy_id, revision),
    CONSTRAINT ck_api_proxy_revisions_positive CHECK (revision > 0),
    -- exactly one target style: a direct URL or a named target server
    CONSTRAINT ck_api_proxy_revisions_target CHECK ((target_url IS NULL) <> (target_server IS NULL))
);

-- ---------------------------------------------------------------------------
-- Deployments: at most one revision of a proxy per environment, and base paths
-- are unique per environment so routes can never collide.
-- RESTRICT keeps environments, proxies and revisions from disappearing under live traffic.
-- ---------------------------------------------------------------------------
CREATE TABLE deployments (
    id              UUID          PRIMARY KEY,
    environment_id  UUID          NOT NULL REFERENCES environments (id) ON DELETE RESTRICT,
    api_proxy_id    UUID          NOT NULL REFERENCES api_proxies (id) ON DELETE RESTRICT,
    revision_id     UUID          NOT NULL REFERENCES api_proxy_revisions (id) ON DELETE RESTRICT,
    base_path       VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      VARCHAR(255),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_deployments_env_proxy UNIQUE (environment_id, api_proxy_id),
    CONSTRAINT uq_deployments_env_base_path UNIQUE (environment_id, base_path)
);

CREATE INDEX ix_deployments_proxy ON deployments (api_proxy_id);
CREATE INDEX ix_deployments_revision ON deployments (revision_id);

-- ---------------------------------------------------------------------------
-- New permission: manage target servers (environment-level backend config)
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, 'TARGET_SERVER_WRITE'
FROM roles r
WHERE r.code IN ('ORG_ADMIN', 'API_ADMIN', 'OPS_ADMIN');
