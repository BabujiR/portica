-- ---------------------------------------------------------------------------
-- Phase 8: client certificates registered on app credentials (mutual TLS at the gateway)
-- and per-target-server TLS settings (mutual TLS from the gateway to backends).
-- ---------------------------------------------------------------------------

-- A client certificate identifies exactly one credential; the gateway matches the SHA-256
-- fingerprint of the DER encoding against the certificate the client presented.
CREATE TABLE app_certificates (
    id               UUID           PRIMARY KEY,
    credential_id    UUID           NOT NULL REFERENCES app_credentials (id) ON DELETE CASCADE,
    fingerprint      VARCHAR(64)    NOT NULL,
    subject          VARCHAR(1024)  NOT NULL,
    issuer           VARCHAR(1024)  NOT NULL,
    serial_number    VARCHAR(128)   NOT NULL,
    not_before       TIMESTAMPTZ    NOT NULL,
    not_after        TIMESTAMPTZ    NOT NULL,
    certificate      TEXT           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ    NOT NULL,
    updated_by       VARCHAR(255),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_app_certificates_fingerprint UNIQUE (fingerprint)
);

CREATE INDEX ix_app_certificates_credential ON app_certificates (credential_id);

-- Mutual TLS to a target server: the gateway's client certificate chain (PEM), its private key
-- (PKCS #8 PEM, envelope-encrypted like other secrets), CAs to trust instead of the JVM defaults,
-- and whether the server's host name must match its certificate.
ALTER TABLE target_servers
    ADD COLUMN tls_client_certificate    TEXT,
    ADD COLUMN tls_client_key_encrypted  TEXT,
    ADD COLUMN tls_trusted_certificates  TEXT,
    ADD COLUMN tls_verify_hostname       BOOLEAN NOT NULL DEFAULT TRUE;
