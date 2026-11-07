package com.portica.management.rbac;

import com.portica.management.identity.User;
import com.portica.management.organization.Organization;
import com.portica.management.shared.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Grants a {@link Role} to a {@link User} within one {@link Organization}.
 * Organization membership is simply "has at least one role binding".
 */
@Entity
@Table(name = "role_bindings")
public class RoleBinding extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private Role role;

    protected RoleBinding() {
        // JPA
    }

    public RoleBinding(Organization organization, User user, Role role) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.user = Objects.requireNonNull(user, "user");
        this.role = Objects.requireNonNull(role, "role");
    }

    public Organization getOrganization() {
        return organization;
    }

    public User getUser() {
        return user;
    }

    public Role getRole() {
        return role;
    }
}
