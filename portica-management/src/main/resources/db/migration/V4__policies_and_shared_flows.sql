-- Phase 3: policy bundles on API proxy revisions, and shared flows.

ALTER TABLE api_proxy_revisions ADD COLUMN policies JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE api_proxy_revisions ADD COLUMN flows JSONB NOT NULL DEFAULT '{}'::jsonb;

-- ---------------------------------------------------------------------------
-- Shared flows: reusable policy chains with immutable revisions, deployed per
-- environment and called from API proxies by FlowCallout policies.
-- ---------------------------------------------------------------------------
CREATE TABLE shared_flows (
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
    CONSTRAINT uq_shared_flows_org_name UNIQUE (organization_id, name)
);

CREATE TABLE shared_flow_revisions (
    id              UUID           PRIMARY KEY,
    shared_flow_id  UUID           NOT NULL REFERENCES shared_flows (id) ON DELETE CASCADE,
    revision        INTEGER        NOT NULL,
    description     VARCHAR(1024),
    policies        JSONB          NOT NULL DEFAULT '[]'::jsonb,
    steps           JSONB          NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ    NOT NULL,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ    NOT NULL,
    updated_by      VARCHAR(255),
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_shared_flow_revisions UNIQUE (shared_flow_id, revision),
    CONSTRAINT ck_shared_flow_revisions_positive CHECK (revision > 0)
);

CREATE TABLE shared_flow_deployments (
    id              UUID          PRIMARY KEY,
    environment_id  UUID          NOT NULL REFERENCES environments (id) ON DELETE RESTRICT,
    shared_flow_id  UUID          NOT NULL REFERENCES shared_flows (id) ON DELETE RESTRICT,
    revision_id     UUID          NOT NULL REFERENCES shared_flow_revisions (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      VARCHAR(255),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_shared_flow_deployments UNIQUE (environment_id, shared_flow_id)
);

CREATE INDEX ix_shared_flow_deployments_flow ON shared_flow_deployments (shared_flow_id);
CREATE INDEX ix_shared_flow_deployments_revision ON shared_flow_deployments (revision_id);
