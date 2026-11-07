-- Phase 4: API products, developers, developer apps and their credentials, key value maps.

-- ---------------------------------------------------------------------------
-- API products: what apps subscribe to. A product bundles API proxies (and optionally
-- path patterns within them) for a set of environments, with OAuth scopes and a quota.
-- ---------------------------------------------------------------------------
CREATE TABLE api_products (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    approval_type    VARCHAR(16)   NOT NULL,
    access           VARCHAR(16)   NOT NULL,
    environments     JSONB         NOT NULL DEFAULT '[]'::jsonb,
    proxies          JSONB         NOT NULL DEFAULT '[]'::jsonb,
    paths            JSONB         NOT NULL DEFAULT '[]'::jsonb,
    scopes           JSONB         NOT NULL DEFAULT '[]'::jsonb,
    quota_count      BIGINT,
    quota_interval   INTEGER,
    quota_time_unit  VARCHAR(16),
    attributes       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_api_products_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_api_products_approval CHECK (approval_type IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_api_products_access CHECK (access IN ('PUBLIC', 'PRIVATE', 'INTERNAL')),
    CONSTRAINT ck_api_products_quota CHECK (
        (quota_count IS NULL AND quota_interval IS NULL AND quota_time_unit IS NULL)
        OR (quota_count > 0 AND quota_interval > 0 AND quota_time_unit IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- Developers (API consumers, not console users) and their apps.
-- ---------------------------------------------------------------------------
CREATE TABLE developers (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email            VARCHAR(320)  NOT NULL,
    first_name       VARCHAR(128)  NOT NULL,
    last_name        VARCHAR(128)  NOT NULL,
    user_name        VARCHAR(128),
    status           VARCHAR(16)   NOT NULL,
    attributes       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_developers_org_email UNIQUE (organization_id, email),
    CONSTRAINT ck_developers_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_developers_email_lower CHECK (email = lower(email))
);

CREATE TABLE developer_apps (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    developer_id     UUID          NOT NULL REFERENCES developers (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    callback_url     VARCHAR(2048),
    status           VARCHAR(16)   NOT NULL,
    attributes       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_developer_apps_developer_name UNIQUE (developer_id, name),
    CONSTRAINT ck_developer_apps_status CHECK (status IN ('APPROVED', 'REVOKED'))
);

CREATE INDEX ix_developer_apps_org ON developer_apps (organization_id);

-- Credentials: consumer key + secret pairs. Only SHA-256 hashes are stored; the plain values are
-- returned once, when the credential is created.
CREATE TABLE app_credentials (
    id               UUID          PRIMARY KEY,
    app_id           UUID          NOT NULL REFERENCES developer_apps (id) ON DELETE CASCADE,
    key_hash         VARCHAR(64)   NOT NULL,
    key_prefix       VARCHAR(8)    NOT NULL,
    secret_hash      VARCHAR(64)   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_credentials_key_hash UNIQUE (key_hash),
    CONSTRAINT ck_app_credentials_status CHECK (status IN ('APPROVED', 'REVOKED'))
);

CREATE INDEX ix_app_credentials_app ON app_credentials (app_id);

-- Which API products a credential may call, each with its own approval status.
CREATE TABLE app_credential_products (
    id               UUID          PRIMARY KEY,
    credential_id    UUID          NOT NULL REFERENCES app_credentials (id) ON DELETE CASCADE,
    api_product_id   UUID          NOT NULL REFERENCES api_products (id) ON DELETE RESTRICT,
    status           VARCHAR(16)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_credential_products UNIQUE (credential_id, api_product_id),
    CONSTRAINT ck_app_credential_products_status CHECK (status IN ('APPROVED', 'PENDING', 'REVOKED'))
);

CREATE INDEX ix_app_credential_products_product ON app_credential_products (api_product_id);

-- ---------------------------------------------------------------------------
-- Key value maps: runtime configuration and secrets, per environment (environment_id set)
-- or per organization (environment_id NULL). Encrypted values are AES-GCM ciphertext.
-- ---------------------------------------------------------------------------
CREATE TABLE key_value_maps (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    environment_id   UUID          REFERENCES environments (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    encrypted        BOOLEAN       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_key_value_maps UNIQUE NULLS NOT DISTINCT (organization_id, environment_id, name)
);

CREATE TABLE key_value_map_entries (
    id               UUID          PRIMARY KEY,
    map_id           UUID          NOT NULL REFERENCES key_value_maps (id) ON DELETE CASCADE,
    entry_key        VARCHAR(255)  NOT NULL,
    entry_value      TEXT          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_key_value_map_entries UNIQUE (map_id, entry_key)
);
