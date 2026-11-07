package com.portica.management.sharedflow;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.common.policy.BundleValidator;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.Deployment;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.proxy.InvalidBundleException;
import com.portica.management.shared.SearchTerms;
import com.portica.management.sharedflow.SharedFlowDtos.CreateSharedFlowRequest;
import com.portica.management.sharedflow.SharedFlowDtos.CreateSharedFlowRevisionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared flows, their revisions and per-environment deployments. Deployment rules keep the runtime
 * consistent: a proxy or shared flow can only be deployed when every shared flow it calls is deployed
 * in that environment, a shared flow in use cannot be undeployed, and call cycles are rejected.
 */
@Service
public class SharedFlowService {

    static final String RESOURCE_TYPE = "SharedFlow";
    static final String REVISION_RESOURCE_TYPE = "SharedFlowRevision";

    private final SharedFlowRepository flows;
    private final SharedFlowRevisionRepository revisions;
    private final SharedFlowDeploymentRepository flowDeployments;
    private final DeploymentRepository proxyDeployments;
    private final OrganizationFinder organizations;
    private final EnvironmentFinder environments;
    private final AuditService audit;

    public SharedFlowService(SharedFlowRepository flows, SharedFlowRevisionRepository revisions,
                             SharedFlowDeploymentRepository flowDeployments, DeploymentRepository proxyDeployments,
                             OrganizationFinder organizations, EnvironmentFinder environments, AuditService audit) {
        this.flows = flows;
        this.revisions = revisions;
        this.flowDeployments = flowDeployments;
        this.proxyDeployments = proxyDeployments;
        this.organizations = organizations;
        this.environments = environments;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- shared flows and revisions

    @Transactional(readOnly = true)
    public Page<SharedFlow> list(String org, String q, Pageable pageable) {
        return flows.search(organizations.getActive(org).getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public SharedFlow get(String org, String name) {
        return find(organizations.getActive(org), name);
    }

    @Transactional
    public SharedFlowRevision create(String org, CreateSharedFlowRequest request) {
        Organization organization = organizations.getActive(org);
        if (flows.existsByOrganization_IdAndName(organization.getId(), request.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, request.name());
        }
        SharedFlow flow = flows.save(new SharedFlow(organization, request.name(), request.displayName(),
                request.description()));
        SharedFlowRevision revision = addRevision(flow, request.revision());
        audit.record(organization.getId(), "shared-flow.created", RESOURCE_TYPE, flow.getName(),
                Map.of("revision", revision.getRevision()));
        return revision;
    }

    @Transactional
    public void delete(String org, String name) {
        Organization organization = organizations.getActive(org);
        SharedFlow flow = find(organization, name);
        if (flowDeployments.existsBySharedFlow_Id(flow.getId())) {
            throw new BusinessRuleViolationException(
                    "Shared flow '%s' is deployed; undeploy it from every environment first".formatted(name));
        }
        flows.delete(flow);
        audit.record(organization.getId(), "shared-flow.deleted", RESOURCE_TYPE, name, Map.of());
    }

    @Transactional(readOnly = true)
    public Page<SharedFlowRevision> listRevisions(String org, String name, Pageable pageable) {
        return revisions.findBySharedFlow_IdOrderByRevisionDesc(find(organizations.getActive(org), name).getId(), pageable);
    }

    @Transactional(readOnly = true)
    public SharedFlowRevision getRevision(String org, String name, int revision) {
        return findRevision(find(organizations.getActive(org), name), revision);
    }

    @Transactional
    public SharedFlowRevision createRevision(String org, String name, CreateSharedFlowRevisionRequest request) {
        Organization organization = organizations.getActive(org);
        SharedFlowRevision revision = addRevision(find(organization, name), request);
        audit.record(organization.getId(), "shared-flow.revision.created", REVISION_RESOURCE_TYPE,
                name + "/" + revision.getRevision(), Map.of("policies", revision.policies().size()));
        return revision;
    }

    @Transactional
    public void deleteRevision(String org, String name, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        SharedFlowRevision revision = findRevision(find(organization, name), revisionNumber);
        if (flowDeployments.existsByRevision_Id(revision.getId())) {
            throw new BusinessRuleViolationException(
                    "Revision %d of shared flow '%s' is deployed; undeploy it first".formatted(revisionNumber, name));
        }
        revisions.delete(revision);
        audit.record(organization.getId(), "shared-flow.revision.deleted", REVISION_RESOURCE_TYPE,
                name + "/" + revisionNumber, Map.of());
    }

    // ----------------------------------------------------------------- deployments

    @Transactional
    public DeployOutcome deploy(String org, String env, String name, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        Environment environment = environments.get(organization, env);
        SharedFlow flow = find(organization, name);
        SharedFlowRevision revision = findRevision(flow, revisionNumber);

        Set<String> callees = BundleValidator.referencedSharedFlows(revision.policies());
        Map<String, Set<String>> graph = liveSharedFlowGraph(environment);
        graph.put(name, callees);
        requireDeployed(environment, callees, name);
        requireNoCycle(graph, name);

        SharedFlowDeployment current = flowDeployments.findByEnvironment_IdAndSharedFlow_Id(environment.getId(), flow.getId())
                .orElse(null);
        Integer previous = current == null ? null : current.getRevision().getRevision();
        if (previous != null && previous == revisionNumber) {
            return new DeployOutcome(current, false, previous);
        }
        SharedFlowDeployment deployment;
        if (current != null) {
            current.switchTo(revision);
            deployment = current;
        } else {
            deployment = flowDeployments.save(new SharedFlowDeployment(environment, revision));
        }
        environment.markRuntimeConfigChanged();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("environment", env);
        details.put("revision", revisionNumber);
        details.put("previousRevision", previous);
        audit.record(organization.getId(), "shared-flow.deployed", RESOURCE_TYPE, name + "@" + env, details);
        return new DeployOutcome(deployment, true, previous);
    }

    @Transactional
    public void undeploy(String org, String env, String name, int revisionNumber) {
        Organization organization = organizations.getActive(org);
        Environment environment = environments.get(organization, env);
        SharedFlow flow = find(organization, name);
        SharedFlowDeployment deployment = flowDeployments.findByEnvironment_IdAndSharedFlow_Id(environment.getId(), flow.getId())
                .filter(d -> d.getRevision().getRevision() == revisionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("SharedFlowDeployment",
                        "%s revision %d in %s".formatted(name, revisionNumber, env)));
        List<String> users = callersIn(environment, name);
        if (!users.isEmpty()) {
            throw new BusinessRuleViolationException("Shared flow '%s' is used in '%s' by %s; undeploy those first"
                    .formatted(name, env, String.join(", ", users)));
        }
        flowDeployments.delete(deployment);
        environment.markRuntimeConfigChanged();
        audit.record(organization.getId(), "shared-flow.undeployed", RESOURCE_TYPE, name + "@" + env,
                Map.of("environment", env, "revision", revisionNumber));
    }

    @Transactional(readOnly = true)
    public List<SharedFlowDeployment> listForEnvironment(String org, String env) {
        return flowDeployments.findLive(environments.get(org, env).getId());
    }

    @Transactional(readOnly = true)
    public List<SharedFlowDeployment> listForSharedFlow(String org, String name) {
        return flowDeployments.findBySharedFlow(find(organizations.getActive(org), name).getId());
    }

    /** Used when deploying an API proxy: every called shared flow must be live in the environment. */
    public void requireDeployed(Environment environment, Set<String> sharedFlowNames, String caller) {
        if (sharedFlowNames.isEmpty()) {
            return;
        }
        Set<String> live = new HashSet<>();
        flowDeployments.findLive(environment.getId()).forEach(d -> live.add(d.getSharedFlow().getName()));
        List<String> missing = sharedFlowNames.stream().filter(n -> !n.equals(caller) && !live.contains(n)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException("Shared flow(s) %s must be deployed to '%s' first"
                    .formatted(String.join(", ", missing), environment.getName()));
        }
    }

    // ----------------------------------------------------------------- helpers

    private SharedFlowRevision addRevision(SharedFlow flow, CreateSharedFlowRevisionRequest request) {
        List<PolicyDefinition> policies = request.policies() == null ? List.of() : request.policies();
        List<FlowStep> steps = request.steps() == null ? List.of() : request.steps();
        List<String> problems = BundleValidator.validateSharedFlow(policies, steps);
        if (!problems.isEmpty()) {
            throw new InvalidBundleException(problems);
        }
        return revisions.save(new SharedFlowRevision(flow, flow.nextRevisionNumber(), request.description(), policies, steps));
    }

    private Map<String, Set<String>> liveSharedFlowGraph(Environment environment) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (SharedFlowDeployment d : flowDeployments.findLive(environment.getId())) {
            graph.put(d.getSharedFlow().getName(), BundleValidator.referencedSharedFlows(d.getRevision().policies()));
        }
        return graph;
    }

    private static void requireNoCycle(Map<String, Set<String>> graph, String start) {
        Deque<String> path = new ArrayDeque<>();
        if (cycleFrom(graph, start, path, new HashSet<>())) {
            throw new BusinessRuleViolationException("Shared flow call cycle: " + String.join(" -> ", path));
        }
    }

    private static boolean cycleFrom(Map<String, Set<String>> graph, String node, Deque<String> path, Set<String> done) {
        if (path.contains(node)) {
            path.addLast(node);
            return true;
        }
        if (done.contains(node)) {
            return false;
        }
        path.addLast(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (cycleFrom(graph, next, path, done)) {
                return true;
            }
        }
        path.removeLast();
        done.add(node);
        return false;
    }

    private List<String> callersIn(Environment environment, String sharedFlow) {
        List<String> callers = new java.util.ArrayList<>();
        for (Deployment d : proxyDeployments.findLive(environment.getId())) {
            if (BundleValidator.referencedSharedFlows(d.getRevision().policies()).contains(sharedFlow)) {
                callers.add("API proxy '" + d.getApiProxy().getName() + "'");
            }
        }
        for (SharedFlowDeployment d : flowDeployments.findLive(environment.getId())) {
            if (!d.getSharedFlow().getName().equals(sharedFlow)
                    && BundleValidator.referencedSharedFlows(d.getRevision().policies()).contains(sharedFlow)) {
                callers.add("shared flow '" + d.getSharedFlow().getName() + "'");
            }
        }
        return callers;
    }

    private SharedFlow find(Organization organization, String name) {
        return flows.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private SharedFlowRevision findRevision(SharedFlow flow, int revision) {
        return revisions.findBySharedFlow_IdAndRevision(flow.getId(), revision)
                .orElseThrow(() -> new ResourceNotFoundException(REVISION_RESOURCE_TYPE, flow.getName() + "/" + revision));
    }

    public record DeployOutcome(SharedFlowDeployment deployment, boolean changed, Integer previousRevision) {
    }
}
