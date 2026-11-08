package com.portica.management.developer;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * A TLS client certificate registered on an app credential. A client presenting it to the gateway is
 * identified as that credential's app (VerifyClientCertificate) and gets the credential's API products.
 * Only the public certificate is stored.
 */
@Entity
@Table(name = "app_certificates")
public class AppCertificate extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false, updatable = false)
    private AppCredential credential;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "subject", nullable = false, updatable = false, length = 1024)
    private String subject;

    @Column(name = "issuer", nullable = false, updatable = false, length = 1024)
    private String issuer;

    @Column(name = "serial_number", nullable = false, updatable = false, length = 128)
    private String serialNumber;

    @Column(name = "not_before", nullable = false, updatable = false)
    private Instant notBefore;

    @Column(name = "not_after", nullable = false, updatable = false)
    private Instant notAfter;

    @Column(name = "certificate", nullable = false, updatable = false)
    private String certificate;

    protected AppCertificate() {
        // JPA
    }

    AppCertificate(AppCredential credential, String fingerprint, String subject, String issuer, String serialNumber,
                   Instant notBefore, Instant notAfter, String certificate) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.serialNumber = Objects.requireNonNull(serialNumber, "serialNumber");
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.notAfter = Objects.requireNonNull(notAfter, "notAfter");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
    }

    public AppCredential getCredential() {
        return credential;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSubject() {
        return subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public String getCertificate() {
        return certificate;
    }
}
