package com.portica.management.mcp;

import com.portica.management.mcp.McpDtos.McpServerSpec;
import com.portica.management.mcp.McpDtos.McpServerType;
import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import com.portica.management.shared.JsonSpecs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.Objects;

/** An MCP server Portica serves: OpenAPI-generated tools or a fronted remote server. */
@Entity
@Table(name = "mcp_servers")
public class McpServer extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private McpServerType type;

    @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String specJson;

    /** Static bearer token or OAuth client secret, encrypted. */
    @Column(name = "secret_encrypted", columnDefinition = "text")
    private String secretEncrypted;

    @Column(name = "access_token_encrypted", columnDefinition = "text")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "text")
    private String refreshTokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "token_scope", length = 1024)
    private String tokenScope;

    /** Discovered OAuth endpoints ({@link OAuthMetadata}). */
    @Column(name = "oauth_metadata", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String oauthMetadataJson;

    protected McpServer() {
        // JPA
    }

    public McpServer(Organization organization, String name, McpServerType type) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = name;
    }

    void define(String displayName, String description, McpServerSpec spec) {
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description == null || description.isBlank() ? null : description;
        this.specJson = JsonSpecs.write(spec);
    }

    void storeSecret(String encrypted) {
        this.secretEncrypted = encrypted;
    }

    void storeTokens(String accessEncrypted, String refreshEncrypted, Instant expiresAt, String scope) {
        this.accessTokenEncrypted = accessEncrypted;
        this.refreshTokenEncrypted = refreshEncrypted;
        this.tokenExpiresAt = expiresAt;
        this.tokenScope = scope;
    }

    void clearTokens() {
        storeTokens(null, null, null, null);
    }

    void storeOAuthMetadata(OAuthMetadata metadata) {
        this.oauthMetadataJson = metadata == null ? null : JsonSpecs.write(metadata);
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public McpServerType getType() {
        return type;
    }

    public McpServerSpec spec() {
        return JsonSpecs.read(specJson, McpServerSpec.class);
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public String getAccessTokenEncrypted() {
        return accessTokenEncrypted;
    }

    public String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public String getTokenScope() {
        return tokenScope;
    }

    public OAuthMetadata oauthMetadata() {
        return JsonSpecs.read(oauthMetadataJson, OAuthMetadata.class);
    }

    public boolean servedIn(String environment) {
        McpServerSpec s = spec();
        return s.environments() == null || s.environments().isEmpty() || s.environments().contains(environment);
    }

    /**
     * OAuth endpoints discovered for a remote server (RFC 9728 protected resource metadata, then RFC 8414
     * authorization server metadata).
     */
    public record OAuthMetadata(String resource, String issuer, String authorizationEndpoint, String tokenEndpoint,
                                java.util.List<String> codeChallengeMethods) {
    }
}
