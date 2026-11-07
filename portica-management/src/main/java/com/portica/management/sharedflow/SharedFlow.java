package com.portica.management.sharedflow;

import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/** A reusable chain of policies (e.g. "security": CORS + VerifyAPIKey + Quota) called via FlowCallout. */
@Entity
@Table(name = "shared_flows")
public class SharedFlow extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "latest_revision", nullable = false)
    private int latestRevision;

    protected SharedFlow() {
        // JPA
    }

    public SharedFlow(Organization organization, String name, String displayName, String description) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description;
    }

    public int nextRevisionNumber() {
        return ++latestRevision;
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
