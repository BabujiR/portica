package com.portica.management.environment;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceConflictException;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.sharedflow.SharedFlowDeploymentRepository;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.environment.EnvironmentDtos.CreateEnvironmentRequest;
import com.portica.management.environment.EnvironmentDtos.UpdateEnvironmentRequest;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.shared.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EnvironmentService {

    static final String RESOURCE_TYPE = "Environment";

    private final EnvironmentRepository environments;
    private final OrganizationFinder organizations;
    private final DeploymentRepository deployments;
    private final SharedFlowDeploymentRepository sharedFlowDeployments;
    private final AuditService audit;

    public EnvironmentService(EnvironmentRepository environments, OrganizationFinder organizations,
                              DeploymentRepository deployments, SharedFlowDeploymentRepository sharedFlowDeployments,
                              AuditService audit) {
        this.environments = environments;
        this.organizations = organizations;
        this.deployments = deployments;
        this.sharedFlowDeployments = sharedFlowDeployments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Environment> list(String orgName, String q, Pageable pageable) {
        Organization org = organizations.getActive(orgName);
        return environments.search(org.getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public Environment get(String orgName, String envName) {
        return find(organizations.getActive(orgName), envName);
    }

    @Transactional
    public Environment create(String orgName, CreateEnvironmentRequest request) {
        Organization org = organizations.getActive(orgName);
        Environment created = createInternal(org, request.name(), request.displayName(), request.description());
        audit.record(org.getId(), "environment.created", RESOURCE_TYPE, created.getName(),
                Map.of("displayName", created.getDisplayName()));
        return created;
    }

    /** Used by organization bootstrap; the caller records the audit event. */
    @Transactional
    public Environment createInternal(Organization org, String name, String displayName, String description) {
        if (environments.existsByOrganization_IdAndName(org.getId(), name)) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, name);
        }
        return environments.save(new Environment(org, name, displayName, description));
    }

    @Transactional
    public Environment update(String orgName, String envName, UpdateEnvironmentRequest request) {
        Organization org = organizations.getActive(orgName);
        Environment env = find(org, envName);
        env.update(request.displayName(), request.description());
        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.displayName() != null) {
            changes.put("displayName", request.displayName());
        }
        if (request.description() != null) {
            changes.put("descriptionChanged", true);
        }
        audit.record(org.getId(), "environment.updated", RESOURCE_TYPE, env.getName(), changes);
        return env;
    }

    /**
     * Deletes an environment and its target servers. Refused while any API proxy revision is
     * deployed to it, so a running environment can never be removed by accident.
     */
    @Transactional
    public void delete(String orgName, String envName) {
        Organization org = organizations.getActive(orgName);
        Environment env = find(org, envName);
        if (deployments.existsByEnvironment_Id(env.getId()) || sharedFlowDeployments.existsByEnvironment_Id(env.getId())) {
            throw new BusinessRuleViolationException(
                    "Environment '%s' has deployed API proxies or shared flows; undeploy them first".formatted(envName));
        }
        environments.delete(env);
        audit.record(org.getId(), "environment.deleted", RESOURCE_TYPE, envName, Map.of());
    }

    private Environment find(Organization org, String envName) {
        return environments.findByOrganization_IdAndName(org.getId(), envName)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, envName));
    }
}
