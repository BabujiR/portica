package com.portica.management.rbac;

import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A named set of permissions. Built-in roles are seeded by Flyway and are read-only in Phase 1;
 * {@code organizationId} is reserved for organization-specific custom roles.
 */
@Entity
@Table(name = "roles")
public class Role extends AuditableEntity {

    /** Code of the built-in role that owns an organization. */
    public static final String ORG_ADMIN = "ORG_ADMIN";

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(name = "organization_id")
    private UUID organizationId;

    // Small, static data that is needed on every authorization decision: load eagerly.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 64)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    protected Role() {
        // JPA
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }
}
