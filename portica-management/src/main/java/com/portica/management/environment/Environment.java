package com.portica.management.environment;

import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A deployment target inside an organization (e.g. dev, test, prod). API proxy revisions are
 * deployed per environment from Phase 2.
 */
@Entity
@Table(name = "environments")
public class Environment extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    /** Incremented on every runtime-relevant change; gateways re-sync when it moves. */
    @Column(name = "config_version", nullable = false)
    private long configVersion;

    protected Environment() {
        // JPA
    }

    public Environment(Organization organization, String name, String displayName, String description) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description;
    }

    public void update(String displayName, String description) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.strip();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
    }

    /**
     * Marks the environment's runtime configuration as changed. Because this also bumps the
     * entity's optimistic-lock version, concurrent runtime changes in one environment are
     * serialized: the second commit fails with 409 instead of silently racing.
     */
    public void markRuntimeConfigChanged() {
        configVersion++;
    }

    public long getConfigVersion() {
        return configVersion;
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
}
