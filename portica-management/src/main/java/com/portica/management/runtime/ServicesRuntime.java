package com.portica.management.runtime;

import com.portica.common.http.PorticaHeaders;
import com.portica.common.policy.FaultRule;
import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.management.ai.AiDtos.AiProxySpec;
import com.portica.management.ai.AiDtos.ClientAuth;
import com.portica.management.ai.AiProvider;
import com.portica.management.ai.AiProviderRepository;
import com.portica.management.ai.AiProxy;
import com.portica.management.ai.AiProxyRepository;
import com.portica.management.ai.AiService;
import com.portica.management.deployment.Deployment;
import com.portica.management.deployment.DeploymentRepository;
import com.portica.management.environment.Environment;
import com.portica.management.mcp.McpDtos.McpServerSpec;
import com.portica.management.mcp.McpDtos.McpServerType;
import com.portica.management.mcp.McpDtos.RemoteAuth;
import com.portica.management.mcp.McpProperties;
import com.portica.management.mcp.McpServer;
import com.portica.management.mcp.McpServerRepository;
import com.portica.management.mcp.McpService;
import com.portica.management.mcp.OpenApiTools;
import com.portica.management.proxy.ApiProxyRepository;
import com.portica.management.runtime.RuntimeDtos.RuntimeAiProvider;
import com.portica.management.runtime.RuntimeDtos.RuntimeAiProxy;
import com.portica.management.runtime.RuntimeDtos.RuntimeHttpParameter;
import com.portica.management.runtime.RuntimeDtos.RuntimeMcpServer;
import com.portica.management.runtime.RuntimeDtos.RuntimeMcpTool;
import com.portica.management.runtime.RuntimeDtos.RuntimeProxy;
import com.portica.management.shared.GatewayNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime views of AI proxies and MCP servers. Each one also becomes a generated gateway proxy
 * ({@code ai-<name>}, {@code mcp-<name>}) in the environment's snapshot, so calls pass the gateway's
 * policy engine (credentials, product grants, analytics, traces) before reaching the AI or MCP service:
 * <pre>
 *   client -> gateway /{org}/{env}/ai/{name}/... -> [VerifyAPIKey or OAuthV2, context headers] -> portica-ai-gateway
 *   client -> gateway /{org}/{env}/mcp/{name}    -> [VerifyAPIKey or OAuthV2, context headers] -> portica-mcp
 * </pre>
 */
@Component
public class ServicesRuntime {

    private static final Logger log = LoggerFactory.getLogger(ServicesRuntime.class);

    private final AiProviderRepository aiProviders;
    private final AiProxyRepository aiProxies;
    private final AiService aiService;
    private final McpServerRepository mcpServers;
    private final ApiProxyRepository apiProxies;
    private final DeploymentRepository deployments;
    private final McpProperties properties;

    public ServicesRuntime(AiProviderRepository aiProviders, AiProxyRepository aiProxies, AiService aiService,
                           McpServerRepository mcpServers, ApiProxyRepository apiProxies,
                           DeploymentRepository deployments, McpProperties properties) {
        this.aiProviders = aiProviders;
        this.aiProxies = aiProxies;
        this.aiService = aiService;
        this.mcpServers = mcpServers;
        this.apiProxies = apiProxies;
        this.deployments = deployments;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ gateway proxies

    /** Generated gateway proxies for the AI proxies and MCP servers served in this environment. */
    public List<RuntimeProxy> gatewayProxies(Environment environment) {
        String org = environment.getOrganization().getName();
        String env = environment.getName();
        List<RuntimeProxy> result = new ArrayList<>();
        for (AiProxy p : aiProxies.findByOrganization_IdOrderByName(environment.getOrganization().getId())) {
            if (!p.servedIn(env)) {
                continue;
            }
            AiProxySpec spec = p.spec();
            result.add(new RuntimeProxy(GatewayNames.forAiProxy(p.getName()), revision(p.getVersion()),
                    "/ai/" + p.getName(), properties.aiGatewayUrl(), "/internal/ai/" + org + "/" + env + "/" + p.getName(),
                    5_000, spec.timeoutSeconds() * 1000 + 5_000, policies(spec.auth(), true, null), flows(spec.auth(), null)));
        }
        for (McpServer s : mcpServers.findByOrganization_IdOrderByName(environment.getOrganization().getId())) {
            if (!s.servedIn(env) || (s.getType() == McpServerType.OPENAPI && openApiDeployment(environment, s).isEmpty())) {
                continue;
            }
            McpServerSpec spec = s.spec();
            String metadata = properties.gatewayUrl() + "/.well-known/oauth-protected-resource/" + org + "/" + env
                    + "/mcp/" + s.getName();
            result.add(new RuntimeProxy(GatewayNames.forMcpServer(s.getName()), revision(s.getVersion()),
                    "/mcp/" + s.getName(), properties.mcpUrl(), "/internal/mcp/" + org + "/" + env + "/" + s.getName(),
                    5_000, spec.timeoutSeconds() * 1000 + 5_000, policies(spec.auth(), false, metadata), flows(spec.auth(), metadata)));
        }
        return result;
    }

    private static int revision(long version) {
        return (int) (version % 1_000_000) + 1;
    }

    private List<PolicyDefinition> policies(ClientAuth auth, boolean ai, String resourceMetadata) {
        List<PolicyDefinition> policies = new ArrayList<>();
        policies.add(auth == ClientAuth.OAUTH
                ? new PolicyDefinition("verify-access-token", "OAuthV2", null, null, new LinkedHashMap<>())
                : new PolicyDefinition("verify-api-key", "VerifyAPIKey", null, null, new LinkedHashMap<>()));
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(PorticaHeaders.APP, "{developer.app.name}");
        headers.put(PorticaHeaders.DEVELOPER, "{developer.email}");
        headers.put(PorticaHeaders.API_PRODUCT, "{apiproduct.name}");
        headers.put(PorticaHeaders.INTERNAL_TOKEN, properties.internalToken());
        context.put("setHeaders", headers);
        if (ai) {
            // the AI gateway adds the provider's key; the caller's credential stops here
            context.put("removeHeaders", List.of("authorization", "x-api-key"));
        }
        policies.add(new PolicyDefinition("portica-context", "AssignMessage", null, null, context));
        if (challenges(auth, resourceMetadata)) {
            Map<String, Object> challenge = new LinkedHashMap<>();
            challenge.put("statusCode", 401);
            challenge.put("headers", Map.of("WWW-Authenticate",
                    "Bearer error=\"invalid_token\", resource_metadata=\"" + resourceMetadata + "\""));
            challenge.put("contentType", "application/json");
            challenge.put("body", "{\"error\":\"invalid_token\",\"error_description\":\"{fault.message}\"}");
            policies.add(new PolicyDefinition("oauth-challenge", "RaiseFault", null, null, challenge));
        }
        return policies;
    }

    private static boolean challenges(ClientAuth auth, String resourceMetadata) {
        return resourceMetadata != null && auth == ClientAuth.OAUTH;
    }

    /**
     * Pre-flow: verify the credential, then set context headers. For MCP servers with OAuth, a fault rule
     * answers 401s with {@code WWW-Authenticate: Bearer resource_metadata=...} so MCP clients can
     * discover the authorization server (RFC 9728, MCP authorization spec).
     */
    private static ProxyFlows flows(ClientAuth auth, String resourceMetadata) {
        String authPolicy = auth == ClientAuth.OAUTH ? "verify-access-token" : "verify-api-key";
        FlowPair pre = new FlowPair(List.of(new FlowStep(authPolicy, null), new FlowStep("portica-context", null)), List.of());
        List<FaultRule> faultRules = !challenges(auth, resourceMetadata) ? List.of() : List.of(new FaultRule("oauth-challenge",
                "fault.name == \"MissingAccessToken\" or fault.name == \"InvalidAccessToken\"",
                List.of(new FlowStep("oauth-challenge", null))));
        return new ProxyFlows(pre, List.of(), FlowPair.EMPTY, faultRules, null);
    }

    private Optional<Deployment> openApiDeployment(Environment environment, McpServer server) {
        return apiProxies.findByOrganization_IdAndName(environment.getOrganization().getId(), server.spec().apiProxy())
                .flatMap(proxy -> deployments.findByEnvironment_IdAndApiProxy_Id(environment.getId(), proxy.getId()));
    }

    // ------------------------------------------------------------------ AI gateway feed

    public List<RuntimeAiProvider> aiProviders(Environment environment) {
        return aiProviders.findByOrganization_IdOrderByName(environment.getOrganization().getId()).stream()
                .map((AiProvider p) -> new RuntimeAiProvider(p.getName(), p.getType(), p.getBaseUrl(), aiService.apiKey(p),
                        p.isEnabled(), p.models()))
                .toList();
    }

    public List<RuntimeAiProxy> aiProxies(Environment environment) {
        return aiProxies.findByOrganization_IdOrderByName(environment.getOrganization().getId()).stream()
                .filter(p -> p.servedIn(environment.getName()))
                .map(p -> {
                    AiProxySpec s = p.spec();
                    return new RuntimeAiProxy(p.getName(), s.routes(), s.defaultRoute(), s.allowProviderModels(),
                            s.tokenQuota(), s.pii(), s.timeoutSeconds(), s.maxOutputTokens());
                })
                .toList();
    }

    // ------------------------------------------------------------------ MCP feed

    public List<RuntimeMcpServer> mcpServers(Environment environment) {
        List<RuntimeMcpServer> result = new ArrayList<>();
        for (McpServer s : mcpServers.findByOrganization_IdOrderByName(environment.getOrganization().getId())) {
            if (!s.servedIn(environment.getName())) {
                continue;
            }
            McpServerSpec spec = s.spec();
            if (s.getType() == McpServerType.OPENAPI) {
                Optional<Deployment> deployment = openApiDeployment(environment, s);
                if (deployment.isEmpty()) {
                    continue;
                }
                List<RuntimeMcpTool> tools;
                try {
                    tools = McpService.openApiTools(deployment.get().getRevision(), spec.tools()).stream()
                            .map(ServicesRuntime::runtimeTool).toList();
                } catch (RuntimeException e) {
                    log.warn("MCP server {} in {}: can't generate tools: {}", s.getName(), environment.getName(), e.getMessage());
                    tools = List.of();
                }
                result.add(new RuntimeMcpServer(s.getName(), s.getDisplayName(), s.getDescription(), "OPENAPI",
                        deployment.get().getBasePath(), tools, null, false, spec.tools(), spec.timeoutSeconds()));
            } else {
                boolean token = spec.remote().auth() != RemoteAuth.NONE;
                result.add(new RuntimeMcpServer(s.getName(), s.getDisplayName(), s.getDescription(), "REMOTE", null,
                        List.of(), spec.remote().url(), token, spec.tools(), spec.timeoutSeconds()));
            }
        }
        return result;
    }

    private static RuntimeMcpTool runtimeTool(OpenApiTools.Tool t) {
        return new RuntimeMcpTool(t.name(), t.title(), t.description(), t.inputSchema(), annotations(t),
                t.http().method(), t.http().path(),
                t.http().parameters().stream().map(p -> new RuntimeHttpParameter(p.name(), p.in())).toList(),
                t.http().hasBody(), t.http().bodyContentType());
    }

    private static Map<String, Object> annotations(OpenApiTools.Tool t) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", t.title());
        a.put("readOnlyHint", t.readOnly());
        a.put("destructiveHint", t.http().method().equals("DELETE"));
        a.put("openWorldHint", true);
        return a;
    }
}
