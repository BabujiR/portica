package com.portica.management.deployment;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.common.policy.BundleValidator;
import com.portica.management.audit.AuditService;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.proxy.ApiProxy;
import com.portica.management.proxy.ApiProxyRepository;
import com.portica.management.proxy.ApiProxyRevision;
import com.portica.management.proxy.ApiProxyRevisionRepository;
import com.portica.management.proxy.TargetSpec;
import com.portica.management.sharedflow.SharedFlowService;
import com.portica.management.targetserver.TargetServer;
import com.portica.management.targetserver.TargetServerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deploys proxy revisions to environments. Every change bumps the environment's config version,
 * which is what gateways watch to pick up the change within one sync interval.
 */
@Service
public class DeploymentService {

    static final String RESOURCE_TYPE = "Deployment";

    private final OrganizationFinder organizations;
    private final EnvironmentFinder environments;
    private final ApiProxyRepository proxies;
    private final ApiProxyRevisionRepository revisions;
    private final TargetServerRepository targetServers;
    private final DeploymentRepository deployments;
    private final SharedFlowService sharedFlows;
    private final AuditService audit;

    public DeploymentService(OrganizationFinder organizations, EnvironmentFinder environments,
                             ApiProxyRepository proxies, ApiProxyRevisionRepository revisions,
                             TargetServerRepository targetServers, DeploymentRepository deployments,
                             SharedFlowService sharedFlows, AuditService audit) {
        this.organizations = organizations;
        this.environments = environments;
        this.proxies = proxies;
        this.revisions = revisions;
        this.targetServers = targetServers;
        this.deployments = deployments;
        this.sharedFlows = sharedFlows;
        this.audit = audit;
    }

    /**
     * Deploys a revision. If another revision of the same proxy is live in the environment it is
     * replaced in place: the gateway swaps the route atomically, so there is no gap in service.
     */
    @Transactional
    public DeploymentOutcome deploy(String org, String env, String api, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        Environment environment = environments.get(organization, env);
        ApiProxy proxy = findProxy(organization, api);
        ApiProxyRevision revision = findRevision(proxy, revisionNumber);

        requireUsableTarget(environment, revision);
        sharedFlows.requireDeployed(environment, BundleValidator.referencedSharedFlows(revision.policies()), null);

        Optional<Deployment> sameBasePath = deployments.findByEnvironment_IdAndBasePath(environment.getId(),
                revision.getBasePath());
        if (sameBasePath.isPresent() && !sameBasePath.get().getApiProxy().getId().equals(proxy.getId())) {
            throw new ResourceConflictException("Base path '%s' is already used in '%s' by API proxy '%s'"
                    .formatted(revision.getBasePath(), env, sameBasePath.get().getApiProxy().getName()));
        }

        Optional<Deployment> current = deployments.findByEnvironment_IdAndApiProxy_Id(environment.getId(), proxy.getId());
        Integer previousRevision = current.map(d -> d.getRevision().getRevision()).orElse(null);
        if (previousRevision != null && previousRevision == revisionNumber) {
            return new DeploymentOutcome(current.get(), false, previousRevision); // idempotent
        }
        Deployment deployment;
        if (current.isPresent()) {
            deployment = current.get();
            deployment.switchTo(revision);
        } else {
            deployment = deployments.save(new Deployment(environment, revision));
        }
        environment.markRuntimeConfigChanged();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("environment", env);
        details.put("revision", revisionNumber);
        details.put("previousRevision", previousRevision);
        details.put("basePath", revision.getBasePath());
        audit.record(organization.getId(), "api-proxy.deployed", RESOURCE_TYPE, api + "@" + env, details);
        return new DeploymentOutcome(deployment, true, previousRevision);
    }

    @Transactional
    public void undeploy(String org, String env, String api, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        Environment environment = environments.get(organization, env);
        ApiProxy proxy = findProxy(organization, api);
        Deployment deployment = deployments.findByEnvironment_IdAndApiProxy_Id(environment.getId(), proxy.getId())
                .filter(d -> d.getRevision().getRevision() == revisionNumber)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE,
                        "%s revision %d in %s".formatted(api, revisionNumber, env)));
        deployments.delete(deployment);
        environment.markRuntimeConfigChanged();
        audit.record(organization.getId(), "api-proxy.undeployed", RESOURCE_TYPE, api + "@" + env,
                Map.of("environment", env, "revision", revisionNumber));
    }

    @Transactional(readOnly = true)
    public List<Deployment> listForEnvironment(String org, String env) {
        return deployments.findLive(environments.get(org, env).getId());
    }

    @Transactional(readOnly = true)
    public List<Deployment> listForProxy(String org, String api) {
        return deployments.findByProxy(findProxy(organizations.getActive(org), api).getId());
    }

    private void requireUsableTarget(Environment environment, ApiProxyRevision revision) {
        TargetSpec target = revision.target();
        if (!target.usesServer()) {
            return;
        }
        TargetServer server = targetServers.findByEnvironment_IdAndName(environment.getId(), target.server())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Target server '%s' does not exist in environment '%s'"
                                .formatted(target.server(), environment.getName())));
        if (!server.isEnabled()) {
            throw new BusinessRuleViolationException("Target server '%s' is disabled in environment '%s'"
                    .formatted(target.server(), environment.getName()));
        }
    }

    private ApiProxy findProxy(Organization organization, String api) {
        return proxies.findByOrganization_IdAndName(organization.getId(), api)
                .orElseThrow(() -> new ResourceNotFoundException("ApiProxy", api));
    }

    private ApiProxyRevision findRevision(ApiProxy proxy, int revision) {
        return revisions.findByApiProxy_IdAndRevision(proxy.getId(), revision)
                .orElseThrow(() -> new ResourceNotFoundException("ApiProxyRevision", proxy.getName() + "/" + revision));
    }

    public record DeploymentOutcome(Deployment deployment, boolean changed, Integer previousRevision) {
    }
}
