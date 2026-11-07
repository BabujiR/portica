package com.portica.management.organization;

import com.portica.common.error.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Shared lookups used by every organization-scoped service. Archived organizations are
 * treated as not found for all mutations.
 */
@Component
public class OrganizationFinder {

    static final String RESOURCE_TYPE = "Organization";

    private final OrganizationRepository organizations;

    public OrganizationFinder(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    public Organization getActive(String name) {
        return organizations.findByName(name)
                .filter(Organization::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    /** Takes a row lock on the organization for the rest of the current transaction. */
    public void lock(Organization organization) {
        organizations.lockById(organization.getId())
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, organization.getName()));
    }

    public Organization getAny(String name) {
        return organizations.findByName(name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }
}
