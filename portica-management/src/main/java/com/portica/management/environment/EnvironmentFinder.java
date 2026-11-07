package com.portica.management.environment;

import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import org.springframework.stereotype.Component;

/** Shared environment lookups for services that work inside an environment. */
@Component
public class EnvironmentFinder {

    private final OrganizationFinder organizations;
    private final EnvironmentRepository environments;

    public EnvironmentFinder(OrganizationFinder organizations, EnvironmentRepository environments) {
        this.organizations = organizations;
        this.environments = environments;
    }

    /** Environment of an active organization, or 404. */
    public Environment get(String orgName, String envName) {
        return get(organizations.getActive(orgName), envName);
    }

    public Environment get(Organization org, String envName) {
        return environments.findByOrganization_IdAndName(org.getId(), envName)
                .orElseThrow(() -> new ResourceNotFoundException(EnvironmentService.RESOURCE_TYPE, envName));
    }
}
