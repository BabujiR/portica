package com.portica.management.targetserver;

import com.portica.common.security.Pem;
import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

/** Request and response payloads for the target servers API. */
public final class TargetServerDtos {

    /** Hostname or IPv4/IPv6 literal; no scheme, port or path. */
    static final String HOST_PATTERN = "^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
            + "|^\\[[0-9A-Fa-f:.]+]$";

    private TargetServerDtos() {
    }

    public record TargetServerRequest(
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "must be a hostname or IP address")
            @Schema(example = "orders.dev.internal")
            String host,

            @NotNull @Min(1) @Max(65535)
            @Schema(example = "8443")
            Integer port,

            @NotNull
            @Schema(example = "HTTPS")
            TargetServerProtocol protocol,

            @Schema(description = "Defaults to true")
            Boolean enabled,

            @Size(max = 1024)
            String description,

            @Valid @Schema(description = "TLS towards the backend (HTTPS only); omit for the gateway defaults")
            TargetTlsRequest tls) {
    }

    /**
     * Mutual TLS and trust settings for an HTTPS target server. {@code clientKey} is write-only and stored
     * encrypted; on update, leave it out to keep the stored key (the certificate must then be unchanged).
     */
    public record TargetTlsRequest(
            @Size(max = 65_536) @Schema(description = "Gateway client certificate chain, PEM (leaf first)")
            String clientCertificate,
            @Size(max = 16_384) @Schema(description = "Private key of the client certificate, PKCS #8 PEM (BEGIN PRIVATE KEY)",
                    accessMode = Schema.AccessMode.WRITE_ONLY)
            String clientKey,
            @Size(max = 262_144) @Schema(description = "CA certificates (PEM) to trust instead of the JVM defaults")
            String trustedCertificates,
            @Schema(description = "Check the backend certificate matches the host; defaults to true")
            Boolean verifyHostname) {
    }

    /** TLS settings as stored; the key itself is never returned. */
    public record TargetTlsResponse(String clientCertificate, String clientCertificateSubject,
                                    Instant clientCertificateNotAfter, boolean clientKeyConfigured,
                                    String trustedCertificates, int trustedCertificateCount, boolean verifyHostname) {

        static TargetTlsResponse from(TargetServer t) {
            if (!t.hasCustomTls()) {
                return null;
            }
            X509Certificate leaf = t.getTlsClientCertificate() == null ? null
                    : Pem.certificates(t.getTlsClientCertificate()).get(0);
            int trusted = t.getTlsTrustedCertificates() == null ? 0 : Pem.certificates(t.getTlsTrustedCertificates()).size();
            return new TargetTlsResponse(t.getTlsClientCertificate(),
                    leaf == null ? null : leaf.getSubjectX500Principal().getName(),
                    leaf == null ? null : leaf.getNotAfter().toInstant(), t.getTlsClientKeyEncrypted() != null,
                    t.getTlsTrustedCertificates(), trusted, t.isTlsVerifyHostname());
        }
    }

    public record CreateTargetServerRequest(
            @NotBlank @ResourceName @Schema(example = "orders-backend") String name,
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "must be a hostname or IP address")
            String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotNull TargetServerProtocol protocol,
            Boolean enabled,
            @Size(max = 1024) String description,
            @Valid TargetTlsRequest tls) {
    }

    public record TargetServerResponse(UUID id, String name, String host, int port, String protocol,
                                       boolean enabled, String description, String baseUri, TargetTlsResponse tls,
                                       Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static TargetServerResponse from(TargetServer t) {
            return new TargetServerResponse(t.getId(), t.getName(), t.getHost(), t.getPort(), t.getProtocol().name(),
                    t.isEnabled(), t.getDescription(), t.baseUri(), TargetTlsResponse.from(t), t.getCreatedAt(),
                    t.getCreatedBy(), t.getUpdatedAt(), t.getUpdatedBy());
        }
    }
}
