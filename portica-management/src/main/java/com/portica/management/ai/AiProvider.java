package com.portica.management.ai;

import com.portica.management.ai.AiDtos.AiModel;
import com.portica.management.ai.AiDtos.AiProviderType;
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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** An LLM provider account: endpoint, encrypted API key and the models it serves with their prices. */
@Entity
@Table(name = "ai_providers")
public class AiProvider extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private AiProviderType type;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(name = "models", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String modelsJson;

    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    @Column(name = "api_key_hint", length = 16)
    private String apiKeyHint;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected AiProvider() {
        // JPA
    }

    public AiProvider(Organization organization, String name, AiProviderType type) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = name;
        this.modelsJson = "[]";
        this.enabled = true;
    }

    void define(String displayName, String baseUrl, List<AiModel> models, boolean enabled) {
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.baseUrl = baseUrl;
        this.modelsJson = JsonSpecs.write(models == null ? List.of() : models);
        this.enabled = enabled;
    }

    void storeApiKey(String encrypted, String hint) {
        this.apiKeyEncrypted = encrypted;
        this.apiKeyHint = hint;
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

    public AiProviderType getType() {
        return type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<AiModel> models() {
        AiModel[] models = JsonSpecs.read(modelsJson, AiModel[].class);
        return models == null ? List.of() : Arrays.asList(models);
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public String getApiKeyHint() {
        return apiKeyHint;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
