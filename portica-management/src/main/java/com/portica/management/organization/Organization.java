package com.portica.management.organization;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A tenant. Every other Portica resource belongs to exactly one organization.
 * The {@code name} is immutable and used in API paths.
 */
@Entity
@Table(name = "organizations")
public class Organization extends AuditableEntity {

    @Column(name = "name", nullable = false, unique = true, updatable = false, length = 63)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrganizationStatus status;

    protected Organization() {
        // JPA
    }

    public Organization(String name, String displayName, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName.strip();
        this.description = description;
        this.status = OrganizationStatus.ACTIVE;
    }

    public void update(String displayName, String description) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.strip();
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
    }

    public void archive() {
        this.status = OrganizationStatus.ARCHIVED;
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
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

    public OrganizationStatus getStatus() {
        return status;
    }
}
