-- Phase 7: AI gateway (LLM providers and AI proxies) and MCP servers.

-- ---------------------------------------------------------------------------
-- LLM providers: where AI proxies send calls. The API key is AES-256-GCM encrypted
-- (same key as encrypted key value maps) and never returned by the API.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_providers (
    id                 UUID          PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name               VARCHAR(63)   NOT NULL,
    display_name       VARCHAR(128)  NOT NULL,
    type               VARCHAR(16)   NOT NULL,
    base_url           VARCHAR(2048) NOT NULL,
    models             JSONB         NOT NULL DEFAULT '[]'::jsonb,
    api_key_encrypted  TEXT,
    api_key_hint       VARCHAR(16),
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL,
    created_by         VARCHAR(255),
    updated_at         TIMESTAMPTZ   NOT NULL,
    updated_by         VARCHAR(255),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_ai_providers_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_ai_providers_type CHECK (type IN ('OPENAI', 'ANTHROPIC'))
);

-- ---------------------------------------------------------------------------
-- AI proxies: one OpenAI-compatible endpoint (/{org}/{env}/ai/{name}/v1/...) with model routing
-- and fallback across providers, a token quota per app and PII redaction. The definition is JSON.
-- ---------------------------------------------------------------------------
CREATE TABLE ai_proxies (
    id               UUID          PRIMARY KEY,
    organization_id  UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name             VARCHAR(63)   NOT NULL,
    display_name     VARCHAR(128)  NOT NULL,
    description      VARCHAR(1024),
    spec             JSONB         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_ai_proxies_org_name UNIQUE (organization_id, name)
);

-- ---------------------------------------------------------------------------
-- MCP servers: tools generated from an API proxy's OpenAPI spec, or a remote MCP server Portica
-- fronts. Remote credentials (static token, OAuth client secret, OAuth tokens) are encrypted.
-- ---------------------------------------------------------------------------
CREATE TABLE mcp_servers (
    id                        UUID          PRIMARY KEY,
    organization_id           UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                      VARCHAR(63)   NOT NULL,
    display_name              VARCHAR(128)  NOT NULL,
    description               VARCHAR(1024),
    type                      VARCHAR(16)   NOT NULL,
    spec                      JSONB         NOT NULL,
    secret_encrypted          TEXT,
    access_token_encrypted    TEXT,
    refresh_token_encrypted   TEXT,
    token_expires_at          TIMESTAMPTZ,
    token_scope               VARCHAR(1024),
    oauth_metadata            JSONB,
    created_at                TIMESTAMPTZ   NOT NULL,
    created_by                VARCHAR(255),
    updated_at                TIMESTAMPTZ   NOT NULL,
    updated_by                VARCHAR(255),
    version                   BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_mcp_servers_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_mcp_servers_type CHECK (type IN ('OPENAPI', 'REMOTE'))
);

-- Pending OAuth authorization-code flows (PKCE): single use, short-lived.
CREATE TABLE mcp_oauth_states (
    state          VARCHAR(128)  PRIMARY KEY,
    server_id      UUID          NOT NULL REFERENCES mcp_servers (id) ON DELETE CASCADE,
    code_verifier  VARCHAR(128)  NOT NULL,
    redirect_uri   VARCHAR(2048) NOT NULL,
    return_to      VARCHAR(2048),
    created_by     VARCHAR(320),
    expires_at     TIMESTAMPTZ   NOT NULL
);
