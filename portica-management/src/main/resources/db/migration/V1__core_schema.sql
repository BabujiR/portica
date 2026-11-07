-- Portica management plane: core tenancy, identity and RBAC schema.
-- Every configuration table carries the same audit columns and an optimistic-lock version.

-- ---------------------------------------------------------------------------
-- Organizations (tenants)
-- ---------------------------------------------------------------------------
CREATE TABLE organizations (
    id            UUID          PRIMARY KEY,
    name          VARCHAR(63)   NOT NULL,
    display_name  VARCHAR(128)  NOT NULL,
    description   VARCHAR(1024),
    status        VARCHAR(16)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    created_by    VARCHAR(255),
    updated_at    TIMESTAMPTZ   NOT NULL,
    updated_by    VARCHAR(255),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_organizations_name UNIQUE (name),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- ---------------------------------------------------------------------------
-- Environments (dev / test / prod ...) inside an organization
-- ---------------------------------------------------------------------------
CREATE TABLE environments (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_environments_org_name UNIQUE (organization_id, name)
);

-- ---------------------------------------------------------------------------
-- Users: identities are authenticated by Keycloak; Portica stores the local profile
-- and links it by the token subject. INVITED users have no subject yet.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                UUID          PRIMARY KEY,
    keycloak_subject  VARCHAR(255),
    email             VARCHAR(320),
    display_name      VARCHAR(255)  NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ   NOT NULL,
    updated_by        VARCHAR(255),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_keycloak_subject UNIQUE (keycloak_subject),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_status CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_invited_has_email CHECK (status <> 'INVITED' OR email IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- Roles and their permissions. Built-in roles are seeded in V2; organization_id is
-- reserved for organization-specific custom roles in a later phase.
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id               UUID          PRIMARY KEY,
    code             VARCHAR(64)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    built_in         BOOLEAN       NOT NULL,
    organization_id  UUID          REFERENCES organizations (id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

CREATE TABLE role_permissions (
    role_id     UUID         NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (role_id, permission)
);

-- ---------------------------------------------------------------------------
-- Role bindings: user U holds role R in organization O
-- ---------------------------------------------------------------------------
CREATE TABLE role_bindings (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id          UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id          UUID          NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_role_bindings UNIQUE (organization_id, user_id, role_id)
);

CREATE INDEX ix_role_bindings_user ON role_bindings (user_id);
CREATE INDEX ix_role_bindings_org_role ON role_bindings (organization_id, role_id);

-- ---------------------------------------------------------------------------
-- Audit trail. Append-only; no foreign keys so history survives deletions.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id               UUID          PRIMARY KEY,
    organization_id  UUID,
    actor_subject    VARCHAR(255),
    actor_email      VARCHAR(320),
    action           VARCHAR(128)  NOT NULL,
    resource_type    VARCHAR(64)   NOT NULL,
    resource_name    VARCHAR(255)  NOT NULL,
    details          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    request_id       VARCHAR(64),
    occurred_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_audit_events_org_time ON audit_events (organization_id, occurred_at DESC);
