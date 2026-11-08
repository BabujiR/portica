-- Phase 6: debug trace sessions. A session asks gateways to capture matching calls of one deployed
-- proxy in one environment for a short time; captured transactions are stored in ClickHouse.
CREATE TABLE trace_sessions (
    id                 UUID          PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    environment_id     UUID          NOT NULL REFERENCES environments (id) ON DELETE CASCADE,
    api_proxy_id       UUID          NOT NULL REFERENCES api_proxies (id) ON DELETE CASCADE,
    -- names, so gateways get the session without joins
    organization_name  VARCHAR(63)   NOT NULL,
    environment_name   VARCHAR(63)   NOT NULL,
    api_proxy_name     VARCHAR(63)   NOT NULL,
    filter_header      VARCHAR(128),
    filter_value       VARCHAR(512),
    filter_client_ip   VARCHAR(64),
    max_transactions   INTEGER       NOT NULL,
    created_by         VARCHAR(320),
    created_at         TIMESTAMPTZ   NOT NULL,
    expires_at         TIMESTAMPTZ   NOT NULL,
    stopped_at         TIMESTAMPTZ,
    CONSTRAINT ck_trace_sessions_max CHECK (max_transactions BETWEEN 1 AND 100)
);

CREATE INDEX ix_trace_sessions_active ON trace_sessions (expires_at) WHERE stopped_at IS NULL;
CREATE INDEX ix_trace_sessions_proxy ON trace_sessions (organization_id, api_proxy_id, created_at DESC);
