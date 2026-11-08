package com.portica.management.ai;

import com.portica.management.ai.AiDtos.AiProxySpec;
import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import com.portica.management.shared.JsonSpecs;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.util.Objects;

/** An OpenAI-compatible endpoint served by the AI gateway, with routing, quota and PII rules. */
@Entity
@Table(name = "ai_proxies")
public class AiProxy extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String specJson;

    protected AiProxy() {
        // JPA
    }

    public AiProxy(Organization organization, String name) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = name;
    }

    void define(String displayName, String description, AiProxySpec spec) {
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description == null || description.isBlank() ? null : description;
        this.specJson = JsonSpecs.write(spec);
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

    public AiProxySpec spec() {
        return JsonSpecs.read(specJson, AiProxySpec.class);
    }

    /** True when served in {@code environment} (no environments listed means all). */
    public boolean servedIn(String environment) {
        AiProxySpec s = spec();
        return s.environments() == null || s.environments().isEmpty() || s.environments().contains(environment);
    }
}
