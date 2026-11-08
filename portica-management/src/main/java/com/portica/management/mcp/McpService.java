package com.portica.management.mcp;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.InvalidRequestException;
import com.portica.common.error.ResourceConflictException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.common.mcp.McpClient;
import com.portica.common.mcp.McpClientException;
import com.portica.management.ai.AiDtos.ClientAuth;
import com.portica.management.audit.AuditService;
import com.portica.management.deployment.Deployment;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.environment.EnvironmentFinder;
import com.portica.management.identity.CurrentUserService;
import com.portica.management.mcp.McpDtos.AuthorizeResponse;
import com.portica.management.mcp.McpDtos.CreateMcpServerRequest;
import com.portica.management.mcp.McpDtos.McpServerResponse;
import com.portica.management.mcp.McpDtos.McpServerSpec;
import com.portica.management.mcp.McpDtos.McpServerType;
import com.portica.management.mcp.McpDtos.OAuthStatus;
import com.portica.management.mcp.McpDtos.RemoteAuth;
import com.portica.management.mcp.McpDtos.RemoteRequest;
import com.portica.management.mcp.McpDtos.RemoteResponse;
import com.portica.management.mcp.McpDtos.RemoteSpec;
import com.portica.management.mcp.McpDtos.ToolView;
import com.portica.management.mcp.McpDtos.UpdateMcpServerRequest;
import com.portica.management.organization.Organization;
import com.portica.management.organization.OrganizationFinder;
import com.portica.management.proxy.ApiProxy;
import com.portica.management.proxy.ApiProxyRepository;
import com.portica.management.proxy.ApiProxyRevision;
import com.portica.management.proxy.ApiProxyRevisionRepository;
import com.portica.management.runtime.RuntimeChanges;
import com.portica.management.shared.GatewayNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MCP servers: definitions, tool previews, OAuth sign-in for remote servers. */
@Service
public class McpService {

    static final String RESOURCE_TYPE = "McpServer";
    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final OrganizationFinder organizations;
    private final EnvironmentFinder environments;
    private final McpServerRepository servers;
    private final ApiProxyRepository apiProxies;
    private final ApiProxyRevisionRepository revisions;
    private final DeploymentRepository deployments;
    private final McpOAuthService oauth;
    private final GatewayNames gatewayNames;
    private final RuntimeChanges runtimeChanges;
    private final CurrentUserService currentUsers;
    private final AuditService audit;

    public McpService(OrganizationFinder organizations, EnvironmentFinder environments, McpServerRepository servers,
                      ApiProxyRepository apiProxies, ApiProxyRevisionRepository revisions,
                      DeploymentRepository deployments, McpOAuthService oauth, GatewayNames gatewayNames,
                      RuntimeChanges runtimeChanges, CurrentUserService currentUsers, AuditService audit) {
        this.organizations = organizations;
        this.environments = environments;
        this.servers = servers;
        this.apiProxies = apiProxies;
        this.revisions = revisions;
        this.deployments = deployments;
        this.oauth = oauth;
        this.gatewayNames = gatewayNames;
        this.runtimeChanges = runtimeChanges;
        this.currentUsers = currentUsers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<McpServerResponse> list(String org) {
        return servers.findByOrganization_IdOrderByName(organizations.getActive(org).getId()).stream()
                .map(McpService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public McpServerResponse get(String org, String name) {
        return toResponse(find(organizations.getActive(org), name));
    }

    @Transactional
    public McpServerResponse create(String org, CreateMcpServerRequest r) {
        Organization organization = organizations.getActive(org);
        if (servers.existsByOrganization_IdAndName(organization.getId(), r.name())) {
            throw ResourceConflictException.alreadyExists(RESOURCE_TYPE, r.name());
        }
        gatewayNames.requireFreeGatewayName(organization.getId(), GatewayNames.forMcpServer(r.name()));
        // saved first so its id exists (secrets are encrypted with the id as associated data); the row is
        // inserted at the first flush, which a query below may trigger, so it needs a valid definition already
        McpServer draft = new McpServer(organization, r.name(), r.type());
        draft.define(r.displayName(), r.description(), new McpServerSpec(List.of(), ClientAuth.API_KEY, r.type(), null,
                List.of(), null, DEFAULT_TIMEOUT_SECONDS));
        McpServer server = servers.save(draft);
        apply(organization, server, r.displayName(), r.description(), r.environments(), r.auth(), r.apiProxy(),
                r.tools(), r.remote(), r.timeoutSeconds());
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "mcp-server.created", RESOURCE_TYPE, r.name(), details(server));
        return toResponse(server);
    }

    @Transactional
    public McpServerResponse update(String org, String name, UpdateMcpServerRequest r) {
        Organization organization = organizations.getActive(org);
        McpServer server = find(organization, name);
        RemoteSpec before = server.spec().remote();
        apply(organization, server, r.displayName(), r.description(), r.environments(), r.auth(), r.apiProxy(),
                r.tools(), r.remote(), r.timeoutSeconds());
        RemoteSpec after = server.spec().remote();
        if (before != null && after != null && (!before.url().equals(after.url()) || before.auth() != after.auth()
                || !java.util.Objects.equals(before.clientId(), after.clientId())
                || !java.util.Objects.equals(before.authorizationServer(), after.authorizationServer()))) {
            server.clearTokens();               // tokens were issued for the old server or client
            server.storeOAuthMetadata(null);
        }
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "mcp-server.updated", RESOURCE_TYPE, name, details(server));
        return toResponse(server);
    }

    @Transactional
    public void delete(String org, String name) {
        Organization organization = organizations.getActive(org);
        servers.delete(find(organization, name));
        runtimeChanges.organizationChanged(organization);
        audit.record(organization.getId(), "mcp-server.deleted", RESOURCE_TYPE, name, Map.of());
    }

    private void apply(Organization organization, McpServer server, String displayName, String description,
                       List<String> envs, ClientAuth auth, String apiProxy, List<String> tools, RemoteRequest remote,
                       Integer timeoutSeconds) {
        RemoteSpec remoteSpec = null;
        if (server.getType() == McpServerType.OPENAPI) {
            if (apiProxy == null || apiProxy.isBlank()) {
                throw new InvalidRequestException("'apiProxy' is required for OPENAPI servers");
            }
            apiProxies.findByOrganization_IdAndName(organization.getId(), apiProxy)
                    .orElseThrow(() -> new InvalidRequestException("API proxy '%s' doesn't exist".formatted(apiProxy)));
            if (remote != null) {
                throw new InvalidRequestException("'remote' applies only to REMOTE servers");
            }
        } else {
            if (remote == null) {
                throw new InvalidRequestException("'remote' is required for REMOTE servers");
            }
            if (apiProxy != null) {
                throw new InvalidRequestException("'apiProxy' applies only to OPENAPI servers");
            }
            remoteSpec = remoteSpec(server, remote);
        }
        List<String> environmentsList = envs == null ? List.of() : envs.stream().distinct().toList();
        server.define(displayName, description, new McpServerSpec(environmentsList, auth == null ? ClientAuth.API_KEY : auth,
                server.getType(), server.getType() == McpServerType.OPENAPI ? apiProxy : null,
                tools == null ? List.of() : tools.stream().distinct().toList(), remoteSpec,
                timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds));
    }

    private RemoteSpec remoteSpec(McpServer server, RemoteRequest r) {
        URI url;
        try {
            url = new URI(r.url().strip());
        } catch (java.net.URISyntaxException e) {
            throw new InvalidRequestException("remote.url is not a valid URL");
        }
        if (url.getHost() == null || !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                || url.getFragment() != null) {
            throw new InvalidRequestException("remote.url must be an http(s) URL");
        }
        RemoteAuth auth = r.auth() == null ? RemoteAuth.NONE : r.auth();
        boolean oauthAuth = auth == RemoteAuth.OAUTH_CLIENT_CREDENTIALS || auth == RemoteAuth.OAUTH_AUTHORIZATION_CODE;
        if (oauthAuth && (r.clientId() == null || r.clientId().isBlank())) {
            throw new InvalidRequestException("remote.clientId is required for OAuth");
        }
        if (auth == RemoteAuth.BEARER) {
            if (r.bearerToken() != null) {
                server.storeSecret(r.bearerToken().isBlank() ? null : oauth.encryptSecret(server, r.bearerToken().strip()));
            } else if (server.getSecretEncrypted() == null) {
                throw new InvalidRequestException("remote.bearerToken is required for auth BEARER");
            }
        } else if (oauthAuth) {
            if (r.clientSecret() != null) {
                server.storeSecret(r.clientSecret().isBlank() ? null : oauth.encryptSecret(server, r.clientSecret().strip()));
            }
            if (auth == RemoteAuth.OAUTH_CLIENT_CREDENTIALS && server.getSecretEncrypted() == null) {
                throw new InvalidRequestException("remote.clientSecret is required for client credentials");
            }
        } else {
            server.storeSecret(null);
        }
        return new RemoteSpec(url.toString(), auth, oauthAuth ? r.clientId().strip() : null,
                r.scopes() == null ? List.of() : List.copyOf(r.scopes()),
                r.authorizationServer() == null || r.authorizationServer().isBlank() ? null : r.authorizationServer().strip());
    }

    // ------------------------------------------------------------------ tools

    /**
     * Tools clients will see. OPENAPI: from the revision deployed in {@code environment} (or the latest
     * revision when no environment is given). REMOTE: asked from the server itself.
     */
    @Transactional
    public List<ToolView> tools(String org, String name, String environment) {
        Organization organization = organizations.getActive(org);
        McpServer server = find(organization, name);
        McpServerSpec spec = server.spec();
        if (server.getType() == McpServerType.OPENAPI) {
            ApiProxy proxy = apiProxies.findByOrganization_IdAndName(organization.getId(), spec.apiProxy())
                    .orElseThrow(() -> new BusinessRuleViolationException("API proxy '%s' no longer exists".formatted(spec.apiProxy())));
            ApiProxyRevision revision;
            if (environment != null && !environment.isBlank()) {
                Environment env = environments.get(organization, environment);
                revision = deployments.findByEnvironment_IdAndApiProxy_Id(env.getId(), proxy.getId())
                        .map(Deployment::getRevision)
                        .orElseThrow(() -> new BusinessRuleViolationException("API proxy '%s' isn't deployed in '%s'"
                                .formatted(proxy.getName(), environment)));
            } else {
                revision = revisions.findByApiProxy_IdAndRevision(proxy.getId(), proxy.getLatestRevision())
                        .orElseThrow(() -> new ResourceNotFoundException("ApiProxyRevision", proxy.getName()));
            }
            return openApiTools(revision, spec.tools()).stream()
                    .map(t -> new ToolView(t.name(), t.title(), t.description(), t.inputSchema(), annotations(t), t.http()))
                    .toList();
        }
        // remote: ask the server (token from the same place portica-mcp gets it)
        McpOAuthService.AccessToken token = oauth.accessToken(server.getId());
        McpClient client = new McpClient(URI.create(spec.remote().url()),
                () -> token.token() == null ? Map.of() : Map.of("Authorization", "Bearer " + token.token()),
                Duration.ofSeconds(spec.timeoutSeconds()));
        try {
            client.initialize();
            return client.listTools().stream()
                    .filter(t -> spec.tools().isEmpty() || spec.tools().contains(String.valueOf(t.get("name"))))
                    .map(McpService::remoteTool)
                    .toList();
        } catch (McpClientException e) {
            throw new BusinessRuleViolationException("The MCP server answered: " + e.getMessage());
        }
    }

    /** Tools generated from a revision's OpenAPI spec, filtered by the allowlist. */
    public static List<OpenApiTools.Tool> openApiTools(ApiProxyRevision revision, List<String> allow) {
        if (revision.getOpenApiSpec() == null) {
            throw new BusinessRuleViolationException("Revision %d of API proxy '%s' has no OpenAPI spec; import one first"
                    .formatted(revision.getRevision(), revision.getApiProxy().getName()));
        }
        return OpenApiTools.generate(revision.getOpenApiSpec()).stream()
                .filter(t -> allow == null || allow.isEmpty() || allow.contains(t.name()))
                .toList();
    }

    static Map<String, Object> annotations(OpenApiTools.Tool t) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", t.title());
        a.put("readOnlyHint", t.readOnly());
        a.put("openWorldHint", true);
        if (t.http().method().equals("DELETE")) {
            a.put("destructiveHint", true);
        }
        if (t.http().method().equals("PUT") || t.http().method().equals("DELETE") || t.readOnly()) {
            a.put("idempotentHint", true);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private static ToolView remoteTool(Map<String, Object> t) {
        Object schema = t.get("inputSchema");
        Object annotations = t.get("annotations");
        return new ToolView(String.valueOf(t.get("name")), (String) t.get("title"), (String) t.get("description"),
                schema instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("type", "object"),
                annotations instanceof Map<?, ?> a ? (Map<String, Object>) a : null, null);
    }

    // ------------------------------------------------------------------ OAuth sign-in

    @Transactional
    public AuthorizeResponse authorize(String org, String name) {
        Organization organization = organizations.getActive(org);
        McpServer server = find(organization, name);
        AuthorizeResponse response = oauth.startAuthorization(server, currentUsers.currentUser().actor());
        audit.record(organization.getId(), "mcp-server.oauth.started", RESOURCE_TYPE, name, Map.of());
        return response;
    }

    @Transactional
    public McpServerResponse disconnect(String org, String name) {
        Organization organization = organizations.getActive(org);
        McpServer server = find(organization, name);
        oauth.disconnect(server);
        audit.record(organization.getId(), "mcp-server.oauth.disconnected", RESOURCE_TYPE, name, Map.of());
        return toResponse(server);
    }

    /** Fetches a token now (client credentials) or refreshes it, to check the settings work. */
    public McpServerResponse connect(String org, String name) {
        McpServer server = transactionalFind(org, name);
        oauth.accessToken(server.getId());
        return get(org, name);
    }

    @Transactional(readOnly = true)
    McpServer transactionalFind(String org, String name) {
        return find(organizations.getActive(org), name);
    }

    // ------------------------------------------------------------------ helpers

    private McpServer find(Organization organization, String name) {
        return servers.findByOrganization_IdAndName(organization.getId(), name)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_TYPE, name));
    }

    static McpServerResponse toResponse(McpServer s) {
        McpServerSpec spec = s.spec();
        RemoteResponse remote = null;
        if (spec.remote() != null) {
            RemoteSpec r = spec.remote();
            McpServer.OAuthMetadata m = s.oauthMetadata();
            OAuthStatus status = r.auth() == RemoteAuth.OAUTH_CLIENT_CREDENTIALS || r.auth() == RemoteAuth.OAUTH_AUTHORIZATION_CODE
                    ? new OAuthStatus(s.getAccessTokenEncrypted() != null, s.getTokenExpiresAt(), s.getTokenScope(),
                    m == null ? null : m.issuer(), m == null ? null : m.tokenEndpoint(),
                    m == null ? null : m.authorizationEndpoint(), s.getRefreshTokenEncrypted() != null)
                    : null;
            remote = new RemoteResponse(r.url(), r.auth(), r.clientId(), r.scopes(), r.authorizationServer(),
                    s.getSecretEncrypted() != null, status);
        }
        return new McpServerResponse(s.getId(), s.getName(), s.getDisplayName(), s.getDescription(), spec.environments(),
                spec.auth(), s.getType(), spec.apiProxy(), spec.tools(), remote, spec.timeoutSeconds(),
                GatewayNames.forMcpServer(s.getName()), "/mcp/" + s.getName(),
                s.getCreatedAt(), s.getCreatedBy(), s.getUpdatedAt(), s.getUpdatedBy());
    }

    private static Map<String, Object> details(McpServer s) {
        McpServerSpec spec = s.spec();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", s.getType().name());
        d.put("environments", spec.environments());
        d.put("auth", spec.auth().name());
        Optional.ofNullable(spec.apiProxy()).ifPresent(p -> d.put("apiProxy", p));
        Optional.ofNullable(spec.remote()).ifPresent(r -> {
            d.put("url", r.url());
            d.put("remoteAuth", r.auth().name());
        });
        return d;
    }
}
