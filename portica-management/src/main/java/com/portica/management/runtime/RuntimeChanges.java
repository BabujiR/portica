package com.portica.management.runtime;

import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentRepository;
import com.portica.management.organization.Organization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bumps environment config versions so gateways refetch their snapshots. Products, developers, apps,
 * credentials and organization-scoped key value maps affect every environment of the organization.
 */
@Component
public class RuntimeChanges {

    private final EnvironmentRepository environments;

    public RuntimeChanges(EnvironmentRepository environments) {
        this.environments = environments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void organizationChanged(Organization organization) {
        environments.findByOrganization_Id(organization.getId()).forEach(Environment::markRuntimeConfigChanged);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void environmentChanged(Environment environment) {
        environment.markRuntimeConfigChanged();
    }
}
