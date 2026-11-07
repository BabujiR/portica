package com.portica.management.targetserver;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.shared.SearchTerms;
import com.portica.management.targetserver.TargetServerDtos.CreateTargetServerRequest;
import com.portica.management.targetserver.TargetServerDtos.TargetServerRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TargetServerService {

    static final String RESOURCE_TYPE = "TargetServer";

    private final TargetServerRepository targetServers;
    private final EnvironmentFinder environments;
    private final DeploymentRepository deployments;
    private final AuditService audit;

    public TargetServerService(TargetServerRepository targetServers, EnvironmentFinder environments,
                               DeploymentRepository deployments, AuditService audit) {
        this.targetServers = targetServers;
        this.environments = environments;
        this.deployments = deployments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<TargetServer> list(String org, String env, String q, Pageable pageable) {
        Environment environment = environments.get(org, env);
        return targetServers.search(environment.getId(), SearchTerms.normalize(q), pageable);
    }

    @Transactional(readOnly = true)
    public TargetServer get(String org, String env, String name) {
        return find(environments.get(org, env), name);
    }

    @Transactional
    public TargetServer create(String org, String env, CreateTargetServerRequest request) {
        Environment environment = environments.get(org, env);
        if (targetServers.existsByEnvironment_IdAndName(environment.getId(), request.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, request.name());
        }
        TargetServer created = targetServers.save(new TargetServer(environment, request.name(), request.description(),
                request.host(), request.port(), request.protocol(), !Boolean.FALSE.equals(request.enabled())));
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.created", RESOURCE_TYPE,
                env + "/" + created.getName(), details(created));
        return created;
    }

    /** Full replacement of the target server's settings. Live proxies pick up the change on the next gateway sync. */
    @Transactional
    public TargetServer update(String org, String env, String name, TargetServerRequest request) {
        Environment environment = environments.get(org, env);
        TargetServer server = find(environment, name);
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        if (!enabled && server.isEnabled() && deployments.isTargetServerInUse(environment.getId(), name)) {
            throw new BusinessRuleViolationException(
                    "Target server '%s' is used by a deployed API proxy in '%s'; undeploy it before disabling"
                            .formatted(name, env));
        }
        server.update(request.description(), request.host(), request.port(), request.protocol(), enabled);
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.updated", RESOURCE_TYPE,
                env + "/" + name, details(server));
        return server;
    }

    @Transactional
    public void delete(String org, String env, String name) {
        Environment environment = environments.get(org, env);
        TargetServer server = find(environment, name);
        if (deployments.isTargetServerInUse(environment.getId(), name)) {
            throw new BusinessRuleViolationException(
                    "Target server '%s' is used by a deployed API proxy in '%s'; undeploy it first".formatted(name, env));
        }
        targetServers.delete(server);
        environment.markRuntimeConfigChanged();
        audit.record(environment.getOrganization().getId(), "target-server.deleted", RESOURCE_TYPE,
                env + "/" + name, Map.of());
    }

    private TargetServer find(Environment environment, String name) {
        return targetServers.findByEnvironment_IdAndName(environment.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    private static Map<String, Object> details(TargetServer server) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("baseUri", server.baseUri());
        details.put("enabled", server.isEnabled());
        return details;
    }
}
