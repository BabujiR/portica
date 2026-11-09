package com.portica.management.targetserver;

import com.portica.management.environment.Environment;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A named backend endpoint defined per environment. Proxy revisions reference it by name,
 * so the same revision can be promoted from dev to prod and reach each environment's backend.
 */
@Entity
@Table(name = "target_servers")
public class TargetServer extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 8)
    private TargetServerProtocol protocol;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "tls_client_certificate")
    private String tlsClientCertificate;

    @Column(name = "tls_client_key_encrypted")
    private String tlsClientKeyEncrypted;

    @Column(name = "tls_trusted_certificates")
    private String tlsTrustedCertificates;

    @Column(name = "tls_verify_hostname", nullable = false)
    private boolean tlsVerifyHostname = true;

    protected TargetServer() {
        // JPA
    }

    public TargetServer(Environment environment, String name, String description, String host, int port,
                        TargetServerProtocol protocol, boolean enabled) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.name = Objects.requireNonNull(name, "name");
        update(description, host, port, protocol, enabled);
    }

    public final void update(String description, String host, int port, TargetServerProtocol protocol,
                             boolean enabled) {
        this.description = description;
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.enabled = enabled;
    }

    /** Base URI such as {@code https://orders.internal:8443}. */
    public String baseUri() {
        return protocol.scheme() + "://" + host + ":" + port;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public TargetServerProtocol getProtocol() {
        return protocol;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * TLS settings towards this backend: client certificate chain and encrypted key for mutual TLS (both or
     * neither), CA certificates to trust instead of the JVM defaults, and host name verification.
     */
    public void updateTls(String clientCertificate, String clientKeyEncrypted, String trustedCertificates,
                          boolean verifyHostname) {
        this.tlsClientCertificate = clientCertificate;
        this.tlsClientKeyEncrypted = clientKeyEncrypted;
        this.tlsTrustedCertificates = trustedCertificates;
        this.tlsVerifyHostname = verifyHostname;
    }

    /** Whether the gateway needs a dedicated TLS client for this backend. */
    public boolean hasCustomTls() {
        return tlsClientCertificate != null || tlsTrustedCertificates != null || !tlsVerifyHostname;
    }

    public String getTlsClientCertificate() {
        return tlsClientCertificate;
    }

    public String getTlsClientKeyEncrypted() {
        return tlsClientKeyEncrypted;
    }

    public String getTlsTrustedCertificates() {
        return tlsTrustedCertificates;
    }

    public boolean isTlsVerifyHostname() {
        return tlsVerifyHostname;
    }

    /** Associated data binding the encrypted client key to this row. */
    public String keyAssociatedData() {
        return "target-server:" + environment.getId() + ":" + name;
    }
}
