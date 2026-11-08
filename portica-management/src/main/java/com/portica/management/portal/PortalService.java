package com.portica.management.portal;

import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.analytics.AnalyticsService;
import com.portica.management.deployment.Deployment;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.developer.AppCredential;
import com.portica.management.developer.Developer;
import com.portica.management.developer.DeveloperApp;
import com.portica.management.developer.DeveloperDtos.AppResponse;
import com.portica.management.developer.DeveloperDtos.CreateAppRequest;
import com.portica.management.developer.DeveloperDtos.CreateCredentialRequest;
import com.portica.management.developer.DeveloperDtos.CreateDeveloperRequest;
import com.portica.management.developer.DeveloperDtos.IssuedCredential;
import com.portica.management.developer.DeveloperService;
import com.portica.management.developer.DeveloperStatus;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentRepository;
import com.portica.management.mcp.McpProperties;
import com.portica.management.mcp.McpServer;
import com.portica.management.mcp.McpServerRepository;
import com.portica.management.mcp.McpService;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.portal.PortalDtos.ApiDeployment;
import com.portica.management.portal.PortalDtos.ApiDocs;
import com.portica.management.portal.PortalDtos.Catalog;
import com.portica.management.portal.PortalDtos.CatalogApi;
import com.portica.management.portal.PortalDtos.CatalogMcpServer;
import com.portica.management.portal.PortalDtos.CatalogProduct;
import com.portica.management.portal.PortalDtos.DocOperation;
import com.portica.management.portal.PortalDtos.McpEndpoint;
import com.portica.management.portal.PortalDtos.McpTools;
import com.portica.management.portal.PortalDtos.PortalAppRequest;
import com.portica.management.portal.PortalDtos.PortalKeyRequest;
import com.portica.management.portal.PortalDtos.RegisterRequest;
import com.portica.management.portal.PortalDtos.Usage;
import com.portica.management.product.ApiProduct;
import com.portica.management.product.ApiProductRepository;
import com.portica.management.product.ProductAccess;
import com.portica.management.product.ProductQuota;
import com.portica.management.proxy.ApiProxy;
import com.portica.management.proxy.ApiProxyRepository;
import com.portica.management.proxy.ApiProxyRevision;
import com.portica.management.proxy.openapi.OpenApiDocument;
import com.portica.management.proxy.openapi.OpenApiParser;
import com.portica.management.shared.GatewayNames;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The developer portal's view of an organization. Only PUBLIC API products are visible and requestable;
 * APIs and MCP servers appear when a public product grants them. Every app operation is scoped to the
 * calling developer, whose e-mail address the portal takes from the developer's verified token.
 */
@Service
public class PortalService {

    private final OrganizationFinder organizations;
    private final ApiProductRepository products;
    private final ApiProxyRepository proxies;
    private final DeploymentRepository deployments;
    private final McpServerRepository mcpServers;
    private final McpService mcp;
    private final McpProperties urls;
    private final OpenApiParser openApiParser;
    private final DeveloperService developers;
    private final AnalyticsService analytics;
    private final EnvironmentRepository environments;

    public PortalService(OrganizationFinder organizations, EnvironmentRepository environments,
                         ApiProductRepository products, ApiProxyRepository proxies,
                         DeploymentRepository deployments, McpServerRepository mcpServers, McpService mcp,
                         McpProperties urls, OpenApiParser openApiParser, DeveloperService developers,
                         AnalyticsService analytics) {
        this.organizations = organizations;
        this.environments = environments;
        this.products = products;
        this.proxies = proxies;
        this.deployments = deployments;
        this.mcpServers = mcpServers;
        this.mcp = mcp;
        this.urls = urls;
        this.openApiParser = openApiParser;
        this.developers = developers;
        this.analytics = analytics;
    }

    // ------------------------------------------------------------------ catalog

    @Transactional(readOnly = true)
    public Catalog catalog(String org) {
        Organization organization = organizations.getActive(org);
        List<ApiProduct> visible = publicProducts(organization);
        Map<String, List<String>> productsByProxy = new LinkedHashMap<>();
        visible.forEach(p -> p.proxies().forEach(proxy ->
                productsByProxy.computeIfAbsent(proxy, k -> new ArrayList<>()).add(p.getName())));

        List<CatalogApi> apis = new ArrayList<>();
        List<CatalogMcpServer> servers = new ArrayList<>();
        for (McpServer server : mcpServers.findByOrganization_IdOrderByName(organization.getId())) {
            List<String> granting = productsByProxy.get(GatewayNames.forMcpServer(server.getName()));
            if (granting == null) {
                continue;
            }
            List<McpEndpoint> endpoints = environmentsOf(organization, visible, granting).stream()
                    .filter(server::servedIn)
                    .map(env -> new McpEndpoint(env, gatewayUrl(org, env, "/mcp/" + server.getName())))
                    .toList();
            servers.add(new CatalogMcpServer(server.getName(), server.getDisplayName(), server.getDescription(),
                    server.getType().name(), granting, endpoints));
        }
        for (Map.Entry<String, List<String>> entry : productsByProxy.entrySet()) {
            // AI and MCP gateway proxies have no API proxy row: MCP servers are listed above
            ApiProxy proxy = proxies.findByOrganization_IdAndName(organization.getId(), entry.getKey()).orElse(null);
            if (proxy == null) {
                continue;
            }
            Set<String> environments = environmentsOf(organization, visible, entry.getValue());
            List<ApiDeployment> live = deployments.findByProxy(proxy.getId()).stream()
                    .filter(d -> environments.contains(d.getEnvironment().getName()))
                    .sorted(Comparator.comparing(d -> d.getEnvironment().getName()))
                    .map(d -> new ApiDeployment(d.getEnvironment().getName(), d.getRevision().getRevision(),
                            d.getBasePath(), gatewayUrl(org, d.getEnvironment().getName(), d.getBasePath())))
                    .toList();
            boolean documented = live.stream().anyMatch(d -> revisionOf(proxy, d.environment()).getOpenApiSpec() != null);
            apis.add(new CatalogApi(proxy.getName(), proxy.getDisplayName(), proxy.getDescription(), entry.getValue(),
                    live, documented));
        }
        apis.sort(Comparator.comparing(CatalogApi::name));
        List<CatalogProduct> productViews = visible.stream().map(p -> new CatalogProduct(p.getName(), p.getDisplayName(),
                p.getDescription(), p.getApprovalType(), p.environments(), p.proxies(), p.scopes(), quota(p.quota()))).toList();
        return new Catalog(org, productViews, apis, servers);
    }

    /** Documentation of the revision live in {@code environment}: operations plus the OpenAPI document. */
    @Transactional(readOnly = true)
    public ApiDocs docs(String org, String api, String environment) {
        Organization organization = organizations.getActive(org);
        requireVisibleApi(organization, api);
        ApiProxy proxy = proxies.findByOrganization_IdAndName(organization.getId(), api)
                .orElseThrow(() -> new ResourceNotFoundException("API", api));
        ApiProxyRevision revision = revisionOf(proxy, environment);
        String spec = revision.getOpenApiSpec();
        OpenApiDocument document = spec == null ? null : openApiParser.parse(spec);
        Deployment deployment = deploymentOf(proxy, environment);
        return new ApiDocs(proxy.getName(), proxy.getDisplayName(), proxy.getDescription(), environment,
                revision.getRevision(), gatewayUrl(org, environment, deployment.getBasePath()),
                document == null ? proxy.getDisplayName() : document.title(), document == null ? null : document.apiVersion(),
                document == null ? List.of() : document.operations().stream()
                        .map(o -> new DocOperation(o.method(), o.path(), o.operationId(), o.summary())).toList(),
                spec);
    }

    @Transactional
    public McpTools mcpTools(String org, String server, String environment) {
        Organization organization = organizations.getActive(org);
        requireVisibleApi(organization, GatewayNames.forMcpServer(server));
        return new McpTools(server, environment, mcp.tools(org, server, environment));
    }

    // ------------------------------------------------------------------ the developer

    @Transactional(readOnly = true)
    public Developer me(String org, String email) {
        return developers.get(org, email);
    }

    @Transactional
    public Developer register(String org, String email, RegisterRequest r) {
        organizations.getActive(org);
        return developers.create(org, new CreateDeveloperRequest(email, r.firstName(), r.lastName(), null,
                Map.of("registeredVia", "portal")));
    }

    @Transactional(readOnly = true)
    public List<AppResponse> apps(String org, String email) {
        active(org, email);
        return developers.listApps(org, email, Pageable.unpaged()).getContent().stream()
                .map(a -> AppResponse.from(a, developers.credentialsOf(a), developers.now())).toList();
    }

    @Transactional(readOnly = true)
    public AppResponse app(String org, String email, String app) {
        active(org, email);
        DeveloperApp a = developers.getApp(org, email, app);
        return AppResponse.from(a, developers.credentialsOf(a), developers.now());
    }

    @Transactional
    public IssuedCredential createApp(String org, String email, PortalAppRequest r) {
        Developer developer = active(org, email);
        requirePublic(developer.getOrganization(), r.apiProducts());
        return developers.createApp(org, email, new CreateAppRequest(r.name(), r.displayName(), r.description(),
                r.callbackUrl(), r.apiProducts(), null, Map.of()));
    }

    @Transactional
    public void deleteApp(String org, String email, String app) {
        active(org, email);
        developers.deleteApp(org, email, app);
    }

    @Transactional
    public IssuedCredential createKey(String org, String email, String app, PortalKeyRequest r) {
        Developer developer = active(org, email);
        if (r.apiProducts() != null) {
            requirePublic(developer.getOrganization(), r.apiProducts());
        }
        return developers.createCredential(org, email, app, new CreateCredentialRequest(r.apiProducts(), r.expiresInSeconds()));
    }

    @Transactional
    public void deleteKey(String org, String email, String app, java.util.UUID keyId) {
        active(org, email);
        developers.deleteCredential(org, email, app, keyId);
    }

    @Transactional
    public AppCredential addCertificate(String org, String email, String app, java.util.UUID keyId, String pem) {
        active(org, email);
        return developers.addCertificate(org, email, app, keyId, pem);
    }

    @Transactional
    public AppCredential removeCertificate(String org, String email, String app, java.util.UUID keyId, String fingerprint) {
        active(org, email);
        return developers.removeCertificate(org, email, app, keyId, fingerprint);
    }

    /** The developer's own traffic: totals, a time series and the busiest apps and APIs. */
    public Usage usage(String org, String email, Instant from, Instant to, String environment) {
        Developer developer = developers.get(org, email);
        String who = developer.getEmail();
        return new Usage(analytics.summary(org, from, to, environment, null, who),
                analytics.timeseries(org, from, to, environment, null, who),
                analytics.top(org, "app", from, to, environment, null, 10, who),
                analytics.top(org, "api_proxy", from, to, environment, null, 10, who));
    }

    // ------------------------------------------------------------------ helpers

    private List<ApiProduct> publicProducts(Organization organization) {
        return products.findByOrganization_Id(organization.getId()).stream()
                .filter(p -> p.getAccess() == ProductAccess.PUBLIC)
                .sorted(Comparator.comparing(ApiProduct::getName))
                .toList();
    }

    private void requirePublic(Organization organization, List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new InvalidRequestException("apiProducts: choose at least one API product");
        }
        Set<String> visible = new LinkedHashSet<>();
        publicProducts(organization).forEach(p -> visible.add(p.getName()));
        for (String name : names) {
            if (!visible.contains(name)) {
                throw new ResourceNotFoundException("ApiProduct", name);
            }
        }
    }

    private void requireVisibleApi(Organization organization, String proxyName) {
        boolean visible = publicProducts(organization).stream().anyMatch(p -> p.proxies().contains(proxyName));
        if (!visible) {
            throw new ResourceNotFoundException("API", proxyName);
        }
    }

    /** Environments where at least one of {@code granting} is offered. */
    private Set<String> environmentsOf(Organization organization, List<ApiProduct> visible, List<String> granting) {
        Set<String> result = new java.util.TreeSet<>();
        List<String> all = environments.findByOrganization_Id(organization.getId()).stream().map(Environment::getName).toList();
        visible.stream().filter(p -> granting.contains(p.getName()))
                .forEach(p -> all.stream().filter(p::availableIn).forEach(result::add));
        return result;
    }

    private Deployment deploymentOf(ApiProxy proxy, String environment) {
        return deployments.findByProxy(proxy.getId()).stream()
                .filter(d -> d.getEnvironment().getName().equals(environment))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", proxy.getName() + " in " + environment));
    }

    private ApiProxyRevision revisionOf(ApiProxy proxy, String environment) {
        return deploymentOf(proxy, environment).getRevision();
    }

    private Developer active(String org, String email) {
        Developer developer = developers.get(org, email);
        if (developer.getStatus() != DeveloperStatus.ACTIVE) {
            throw new AccessDeniedException("Your developer account is inactive");
        }
        return developer;
    }

    private String gatewayUrl(String org, String env, String path) {
        return urls.gatewayUrl() + "/" + org + "/" + env + path;
    }

    private static String quota(ProductQuota q) {
        return q == null ? null : q.count() + " per " + (q.interval() == 1 ? "" : q.interval() + " ") + q.timeUnit()
                + (q.interval() == 1 ? "" : "s");
    }
}
