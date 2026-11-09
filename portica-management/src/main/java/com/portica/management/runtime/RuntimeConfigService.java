package com.portica.management.runtime;

import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.deployment.Deployment;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.environment.EnvironmentRepository;
import com.portica.management.organization.OrganizationStatus;
import com.portica.management.proxy.ApiProxyRevision;
import com.portica.management.proxy.TargetSpec;
import com.portica.management.runtime.RuntimeDtos.EnvironmentVersion;
import com.portica.management.runtime.RuntimeDtos.RuntimeEnvironmentConfig;
import com.portica.management.runtime.RuntimeDtos.RuntimeIndex;
import com.portica.management.runtime.RuntimeDtos.RuntimeProxy;
import com.portica.management.runtime.RuntimeDtos.RuntimeApiProduct;
import com.portica.management.runtime.RuntimeDtos.RuntimeCredential;
import com.portica.management.runtime.RuntimeDtos.RuntimeTargetTls;
import com.portica.management.developer.AppCertificate;
import com.portica.management.kvm.KvmCipher;
import com.portica.management.runtime.RuntimeDtos.RuntimeKeyValueMap;
import com.portica.management.runtime.RuntimeDtos.RuntimeQuota;
import com.portica.management.runtime.RuntimeDtos.RuntimeSharedFlow;
import com.portica.management.developer.AppCredential;
import com.portica.management.developer.AppCredentialRepository;
import com.portica.management.developer.DeveloperApp;
import com.portica.management.developer.ProductGrantStatus;
import com.portica.management.kvm.KeyValueMapService;
import com.portica.management.product.ApiProduct;
import com.portica.management.product.ApiProductRepository;
import com.portica.management.product.ProductQuota;
import com.portica.management.sharedflow.SharedFlowDeploymentRepository;
import com.portica.management.targetserver.TargetServer;
import com.portica.management.targetserver.TargetServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the read-only runtime view gateways consume. Target servers are resolved here, so a
 * gateway only ever deals with concrete URIs.
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentFinder environments;
    private final DeploymentRepository deployments;
    private final TargetServerRepository targetServers;
    private final SharedFlowDeploymentRepository sharedFlowDeployments;
    private final ApiProductRepository products;
    private final AppCredentialRepository credentials;
    private final KeyValueMapService keyValueMaps;
    private final ServicesRuntime services;
    private final KvmCipher cipher;

    public RuntimeConfigService(EnvironmentRepository environmentRepository, EnvironmentFinder environments,
                                DeploymentRepository deployments, TargetServerRepository targetServers,
                                SharedFlowDeploymentRepository sharedFlowDeployments, ApiProductRepository products,
                                AppCredentialRepository credentials, KeyValueMapService keyValueMaps,
                                ServicesRuntime services, KvmCipher cipher) {
        this.services = services;
        this.cipher = cipher;
        this.environmentRepository = environmentRepository;
        this.environments = environments;
        this.deployments = deployments;
        this.targetServers = targetServers;
        this.sharedFlowDeployments = sharedFlowDeployments;
        this.products = products;
        this.credentials = credentials;
        this.keyValueMaps = keyValueMaps;
    }

    @Transactional(readOnly = true)
    public RuntimeIndex index() {
        List<EnvironmentVersion> versions = environmentRepository.findRuntimeVersions(OrganizationStatus.ACTIVE).stream()
                .map(row -> new EnvironmentVersion((String) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();
        return new RuntimeIndex(Instant.now(), versions);
    }

    /** Consistent snapshot: version, deployments and target servers are read in one repeatable-read transaction. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RuntimeEnvironmentConfig snapshot(String org, String env) {
        Environment environment;
        try {
            environment = environments.get(org, env);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException("Runtime environment", org + "/" + env);
        }
        Map<String, TargetServer> servers = targetServers.findByEnvironment_Id(environment.getId()).stream()
                .collect(Collectors.toMap(TargetServer::getName, Function.identity()));

        List<RuntimeProxy> proxies = new ArrayList<>();
        for (Deployment deployment : deployments.findLive(environment.getId())) {
            ApiProxyRevision revision = deployment.getRevision();
            TargetSpec target = revision.target();
            String targetUri = target.url();
            RuntimeTargetTls targetTls = null;
            if (target.usesServer()) {
                TargetServer server = servers.get(target.server());
                if (server == null || !server.isEnabled()) {
                    // Deploy-time checks prevent this; skip rather than route to an unknown host.
                    log.warn("Skipping {} r{} in {}/{}: target server '{}' missing or disabled",
                            deployment.getApiProxy().getName(), revision.getRevision(), org, env, target.server());
                    continue;
                }
                targetUri = server.baseUri();
                targetTls = targetTls(server);
            }
            proxies.add(new RuntimeProxy(deployment.getApiProxy().getName(), revision.getRevision(),
                    revision.getBasePath(), targetUri, target.path(), revision.getConnectTimeoutMs(),
                    revision.getReadTimeoutMs(), revision.policies(), revision.flows(), targetTls));
        }
        // AI proxies and MCP servers reach traffic through generated gateway proxies (Phase 7)
        proxies.addAll(services.gatewayProxies(environment));
        List<RuntimeSharedFlow> sharedFlows = sharedFlowDeployments.findLive(environment.getId()).stream()
                .map(d -> new RuntimeSharedFlow(d.getSharedFlow().getName(), d.getRevision().getRevision(),
                        d.getRevision().policies(), d.getRevision().steps()))
                .toList();
        Instant now = Instant.now();
        List<ApiProduct> offered = products.findByOrganization_Id(environment.getOrganization().getId()).stream()
                .filter(p -> p.availableIn(env)).toList();
        Set<java.util.UUID> offeredIds = offered.stream().map(ApiProduct::getId).collect(Collectors.toSet());
        List<RuntimeCredential> liveCredentials = credentials.findLive(environment.getOrganization().getId()).stream()
                .filter(c -> !c.isExpired(now))
                .map(c -> runtimeCredential(c, offeredIds, now))
                .toList();
        List<RuntimeKeyValueMap> maps = keyValueMaps.runtimeMaps(environment).stream()
                .map(m -> new RuntimeKeyValueMap(m.name(), m.scope(), m.encrypted(), m.entries()))
                .toList();
        return new RuntimeEnvironmentConfig(org, env, environment.getConfigVersion(), now, proxies,
                sharedFlows, offered.stream().map(RuntimeConfigService::runtimeProduct).toList(), liveCredentials, maps);
    }

    /** AI gateway feed for one environment (Phase 7). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RuntimeDtos.RuntimeAiConfig aiSnapshot(String org, String env) {
        Environment environment = runtimeEnvironment(org, env);
        return new RuntimeDtos.RuntimeAiConfig(org, env, environment.getConfigVersion(), Instant.now(),
                services.aiProviders(environment), services.aiProxies(environment));
    }

    /** MCP feed for one environment (Phase 7). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RuntimeDtos.RuntimeMcpConfig mcpSnapshot(String org, String env) {
        Environment environment = runtimeEnvironment(org, env);
        return new RuntimeDtos.RuntimeMcpConfig(org, env, environment.getConfigVersion(), Instant.now(),
                services.mcpServers(environment));
    }

    private Environment runtimeEnvironment(String org, String env) {
        try {
            return environments.get(org, env);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException("Runtime environment", org + "/" + env);
        }
    }

    private static RuntimeApiProduct runtimeProduct(ApiProduct p) {
        ProductQuota q = p.quota();
        return new RuntimeApiProduct(p.getName(), p.proxies(), p.paths(), p.scopes(),
                q == null ? null : new RuntimeQuota(q.count(), q.interval(), q.timeUnit()), p.attributes());
    }

    /** The target server's TLS settings with the client key decrypted, or null for the gateway defaults. */
    private RuntimeTargetTls targetTls(TargetServer server) {
        if (!server.hasCustomTls() || server.getProtocol() != com.portica.management.targetserver.TargetServerProtocol.HTTPS) {
            return null;
        }
        String key = server.getTlsClientKeyEncrypted() == null ? null
                : cipher.decrypt(server.getTlsClientKeyEncrypted(), server.keyAssociatedData());
        return new RuntimeTargetTls(server.getTlsClientCertificate(), key, server.getTlsTrustedCertificates(),
                server.isTlsVerifyHostname());
    }

    private static RuntimeCredential runtimeCredential(AppCredential c, Set<java.util.UUID> offeredProductIds,
                                                       Instant now) {
        DeveloperApp app = c.getApp();
        List<String> granted = c.getProducts().stream()
                .filter(g -> g.getStatus() == ProductGrantStatus.APPROVED)
                .filter(g -> offeredProductIds.contains(g.getApiProduct().getId()))
                .map(g -> g.getApiProduct().getName())
                .toList();
        return new RuntimeCredential(c.getId().toString(), c.getKeyHash(), c.getSecretHash(), c.getExpiresAt(),
                app.getId().toString(), app.getName(), app.attributes(), app.getDeveloper().getId().toString(),
                app.getDeveloper().getEmail(), granted, c.getCertificates().stream()
                        .filter(cert -> cert.getNotAfter().isAfter(now))
                        .map(AppCertificate::getFingerprint).toList());
    }
}
