package com.portica.management.proxy;

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
 * An API proxy: a named, versioned facade in front of a backend. Its behaviour lives in
 * immutable {@link ApiProxyRevision}s; the proxy itself only holds identity and metadata.
 */
@Entity
@Table(name = "api_proxies")
public class ApiProxy extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    /** Highest revision number ever created. Never decreases, even when revisions are deleted. */
    @Column(name = "latest_revision", nullable = false)
    private int latestRevision;

    protected ApiProxy() {
        // JPA
    }

    public ApiProxy(Organization organization, String name, String displayName, String description) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description;
    }

    /**
     * Reserves the next revision number. The proxy's optimistic-lock version makes concurrent
     * reservations fail with 409 rather than produce duplicate numbers.
     */
    public int nextRevisionNumber() {
        return ++latestRevision;
    }

    public void update(String displayName, String description) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.strip();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
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

    public int getLatestRevision() {
        return latestRevision;
    }
}
