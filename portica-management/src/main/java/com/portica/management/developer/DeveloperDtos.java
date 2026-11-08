package com.portica.management.developer;

import com.portica.common.validation.ResourceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeveloperDtos {

    static final long MAX_LIFETIME_SECONDS = 10L * 365 * 24 * 3600;
    static final String URL = "^https?://\\S{1,2000}$";

    private DeveloperDtos() {
    }

    // ----------------------------------------------------------------- developers

    public record CreateDeveloperRequest(
            @NotBlank @Email @Size(max = 320) @Schema(example = "ada@example.com") String email,
            @NotBlank @Size(max = 128) String firstName,
            @NotBlank @Size(max = 128) String lastName,
            @Size(max = 128) String userName,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes) {
    }

    /** Fields left out are unchanged. Setting status INACTIVE stops all of the developer's apps. */
    public record UpdateDeveloperRequest(
            @Size(min = 1, max = 128) String firstName,
            @Size(min = 1, max = 128) String lastName,
            @Size(max = 128) String userName,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes,
            DeveloperStatus status) {
    }

    public record DeveloperResponse(UUID id, String email, String firstName, String lastName, String userName,
                                    DeveloperStatus status, Map<String, String> attributes,
                                    Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static DeveloperResponse from(Developer d) {
            return new DeveloperResponse(d.getId(), d.getEmail(), d.getFirstName(), d.getLastName(), d.getUserName(),
                    d.getStatus(), d.attributes(), d.getCreatedAt(), d.getCreatedBy(), d.getUpdatedAt(), d.getUpdatedBy());
        }
    }

    // ----------------------------------------------------------------- apps

    public record CreateAppRequest(
            @NotBlank @ResourceName @Schema(example = "mobile-app") String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Pattern(regexp = URL) String callbackUrl,
            @Size(max = 50) @Schema(description = "API products for the app's first credential", example = "[\"pets-gold\"]")
            List<@ResourceName String> apiProducts,
            @Min(60) @Max(MAX_LIFETIME_SECONDS) @Schema(description = "Lifetime of the first credential; omit for no expiry")
            Long keyExpiresInSeconds,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes) {
    }

    /** Fields left out are unchanged. Setting status REVOKED stops every credential of the app. */
    public record UpdateAppRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Pattern(regexp = URL + "|^$") String callbackUrl,
            @Size(max = 50) Map<@Size(min = 1, max = 128) String, @Size(max = 2048) String> attributes,
            AppStatus status) {
    }

    public record AppResponse(UUID id, String name, String displayName, String developerEmail, String description,
                              String callbackUrl, AppStatus status, Map<String, String> attributes,
                              List<CredentialResponse> credentials,
                              Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {

        public static AppResponse from(DeveloperApp a, List<AppCredential> credentials, Instant now) {
            return new AppResponse(a.getId(), a.getName(), a.getDisplayName(), a.getDeveloper().getEmail(),
                    a.getDescription(), a.getCallbackUrl(), a.getStatus(), a.attributes(),
                    credentials.stream().sorted(Comparator.comparing(AppCredential::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))).map(c -> CredentialResponse.from(c, now)).toList(),
                    a.getCreatedAt(), a.getCreatedBy(), a.getUpdatedAt(), a.getUpdatedBy());
        }
    }

    /** App created together with its first credential; the plain key and secret appear only here. */
    public record CreateAppResponse(AppResponse app, IssuedCredentialResponse credential) {
    }

    // ----------------------------------------------------------------- credentials

    public record ProductGrantResponse(String apiProduct, ProductGrantStatus status) {

        static ProductGrantResponse from(AppCredentialProduct g) {
            return new ProductGrantResponse(g.getApiProduct().getName(), g.getStatus());
        }
    }

    /** A credential as listed later: the key is identified by its id and first characters only. */
    public record CredentialResponse(UUID id, String keyPrefix, CredentialStatus status, Instant expiresAt,
                                     boolean expired, List<ProductGrantResponse> apiProducts, Instant createdAt,
                                     List<CertificateResponse> certificates) {

        public static CredentialResponse from(AppCredential c, Instant now) {
            return new CredentialResponse(c.getId(), c.getKeyPrefix(), c.getStatus(), c.getExpiresAt(),
                    c.isExpired(now), c.getProducts().stream().map(ProductGrantResponse::from).toList(), c.getCreatedAt(),
                    c.getCertificates().stream().map(CertificateResponse::from).toList());
        }
    }

    /** A client certificate registered on a credential, for mutual TLS at the gateway. */
    public record CertificateResponse(String fingerprint, String subject, String issuer, String serialNumber,
                                      Instant notBefore, Instant notAfter, Instant createdAt) {

        public static CertificateResponse from(AppCertificate c) {
            return new CertificateResponse(c.getFingerprint(), c.getSubject(), c.getIssuer(), c.getSerialNumber(),
                    c.getNotBefore(), c.getNotAfter(), c.getCreatedAt());
        }
    }

    public record AddCertificateRequest(
            @NotBlank @Size(max = 16_384)
            @Schema(description = "The client certificate, PEM (-----BEGIN CERTIFICATE-----). Only the first certificate is registered.")
            String certificate) {
    }

    /** A newly issued credential. Store the secret now: Portica keeps only hashes and can't show it again. */
    public record IssuedCredentialResponse(UUID id, String consumerKey, String consumerSecret, String keyPrefix,
                                           CredentialStatus status, Instant expiresAt,
                                           List<ProductGrantResponse> apiProducts) {

        public static IssuedCredentialResponse from(IssuedCredential issued) {
            AppCredential c = issued.credential();
            return new IssuedCredentialResponse(c.getId(), issued.consumerKey(), issued.consumerSecret(),
                    c.getKeyPrefix(), c.getStatus(), c.getExpiresAt(),
                    c.getProducts().stream().map(ProductGrantResponse::from).toList());
        }
    }

    public record CreateCredentialRequest(
            @Size(max = 50) @Schema(description = "API products for the new credential; omit to copy the app's newest credential")
            List<@ResourceName String> apiProducts,
            @Min(60) @Max(MAX_LIFETIME_SECONDS) @Schema(description = "Lifetime; omit for no expiry") Long expiresInSeconds) {
    }

    /** Fields left out are unchanged. */
    public record UpdateCredentialRequest(
            @Schema(description = "REVOKED stops the credential at the gateways on their next sync") CredentialStatus status,
            @Schema(description = "New expiry time (ISO-8601)") Instant expiresAt) {
    }

    public record RotateCredentialRequest(
            @Min(0) @Max(30L * 24 * 3600) @Schema(description = "Seconds the old credential keeps working (default 86400); 0 revokes it now")
            Long graceSeconds,
            @Min(60) @Max(MAX_LIFETIME_SECONDS) @Schema(description = "Lifetime of the new credential; omit for no expiry")
            Long expiresInSeconds) {
    }

    public record RotatedCredentialResponse(IssuedCredentialResponse credential, CredentialResponse previous) {
    }

    public record AddProductRequest(@NotBlank @ResourceName String apiProduct) {
    }

    public record UpdateGrantRequest(@NotNull ProductGrantStatus status) {
    }

    /** Internal result carrying the plain key and secret out of the service, once. */
    public record IssuedCredential(AppCredential credential, String consumerKey, String consumerSecret) {
    }
}
