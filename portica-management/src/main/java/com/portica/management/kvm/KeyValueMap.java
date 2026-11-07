package com.portica.management.kvm;

import com.portica.management.environment.Environment;
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
 * A named set of key/value entries read by the KeyValueMapOperations policy. Scoped to one
 * environment ({@code environment} set) or to the whole organization ({@code environment} null).
 * Values of encrypted maps are stored encrypted and never returned by the management API.
 */
@Entity
@Table(name = "key_value_maps")
public class KeyValueMap extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", updatable = false)
    private Environment environment;

    @Column(name = "name", nullable = false, updatable = false, length = 63)
    private String name;

    @Column(name = "encrypted", nullable = false, updatable = false)
    private boolean encrypted;

    protected KeyValueMap() {
        // JPA
    }

    KeyValueMap(Organization organization, Environment environment, String name, boolean encrypted) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.environment = environment;
        this.name = Objects.requireNonNull(name, "name");
        this.encrypted = encrypted;
    }

    public Organization getOrganization() {
        return organization;
    }

    /** Null for organization-scoped maps. */
    public Environment getEnvironment() {
        return environment;
    }

    public String getName() {
        return name;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public String scope() {
        return environment == null ? "organization" : "environment";
    }
}
