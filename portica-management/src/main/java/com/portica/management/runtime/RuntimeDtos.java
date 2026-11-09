package com.portica.management.runtime;

import com.portica.common.policy.FlowStep;
import com.portica.management.ai.AiDtos.AiModel;
import com.portica.management.ai.AiDtos.AiProviderType;
import com.portica.management.ai.AiDtos.AiRoute;
import com.portica.management.ai.AiDtos.PiiPolicy;
import com.portica.management.ai.AiDtos.TokenQuota;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Contract between the management plane and gateways. Gateways keep their own copy of these
 * shapes, so changes must stay backwards compatible (add fields; never rename or remove).
 */
public final class RuntimeDtos {

    private RuntimeDtos() {
    }

    /** Cheap "what changed" list a gateway polls frequently. */
    public record RuntimeIndex(Instant generatedAt, List<EnvironmentVersion> environments) {
    }

    public record EnvironmentVersion(String organization, String environment, long configVersion) {
    }

    /** Everything a gateway needs to serve one environment. */
    public record RuntimeEnvironmentConfig(String organization, String environment, long configVersion,
                                           Instant generatedAt, List<RuntimeProxy> proxies,
                                           List<RuntimeSharedFlow> sharedFlows, List<RuntimeApiProduct> apiProducts,
                                           List<RuntimeCredential> credentials,
                                           List<RuntimeKeyValueMap> keyValueMaps) {
    }

    /**
     * @param targetUri  scheme, host and port of the backend, e.g. {@code https://api.example.com:443}
     * @param targetPath path prefix on the backend ("" for none), e.g. {@code /v2}
     * @param targetTls  mutual TLS / trust settings of the target server, or null for the gateway defaults
     */
    public record RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                               int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies,
                               ProxyFlows flows, RuntimeTargetTls targetTls) {

        public RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                            int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies, ProxyFlows flows) {
            this(name, revision, basePath, targetUri, targetPath, connectTimeoutMs, readTimeoutMs, policies, flows, null);
        }
    }

    /**
     * TLS towards a target server: the gateway's client certificate chain and decrypted PKCS #8 key (PEM),
     * CA certificates to trust instead of the JVM defaults, and host name verification. Travels only over
     * the authenticated runtime API, like decrypted key value map entries.
     */
    public record RuntimeTargetTls(String clientCertificate, String clientKey, String trustedCertificates,
                                   boolean verifyHostname) {
    }

    /** A shared flow deployed in the environment, callable by FlowCallout policies. */
    public record RuntimeSharedFlow(String name, int revision, List<PolicyDefinition> policies, List<FlowStep> steps) {
    }

    /** An API product offered in the environment. */
    public record RuntimeApiProduct(String name, List<String> proxies, List<String> paths, List<String> scopes,
                                    RuntimeQuota quota, Map<String, String> attributes) {
    }

    public record RuntimeQuota(long count, int interval, String timeUnit) {
    }

    /**
     * A credential the gateway may honour (approved, of an approved app of an active developer, not yet
     * expired when the snapshot was built). Only hashes travel; {@code apiProducts} lists the products
     * approved for it that are offered in this environment; {@code certificateFingerprints} the SHA-256 of
     * its registered, unexpired client certificates (mutual TLS).
     */
    public record RuntimeCredential(String id, String keyHash, String secretHash, Instant expiresAt, String appId,
                                    String appName, Map<String, String> appAttributes, String developerId,
                                    String developerEmail, List<String> apiProducts,
                                    List<String> certificateFingerprints) {
    }

    /** A key value map readable in this environment, with plain (decrypted) values. */
    public record RuntimeKeyValueMap(String name, String scope, boolean encrypted, Map<String, String> entries) {
    }

    // ------------------------------------------------------------------ AI gateway (Phase 7)

    /** What the AI gateway needs for one environment: providers (with plain API keys) and AI proxies. */
    public record RuntimeAiConfig(String organization, String environment, long configVersion, Instant generatedAt,
                                  List<RuntimeAiProvider> providers, List<RuntimeAiProxy> proxies) {
    }

    public record RuntimeAiProvider(String name, AiProviderType type, String baseUrl, String apiKey, boolean enabled,
                                    List<AiModel> models) {
    }

    public record RuntimeAiProxy(String name, List<AiRoute> routes, String defaultRoute, boolean allowProviderModels,
                                 TokenQuota tokenQuota, PiiPolicy pii, int timeoutSeconds, Integer maxOutputTokens) {
    }

    // ------------------------------------------------------------------ MCP (Phase 7)

    /** What portica-mcp needs for one environment. */
    public record RuntimeMcpConfig(String organization, String environment, long configVersion, Instant generatedAt,
                                   List<RuntimeMcpServer> servers) {
    }

    /**
     * @param proxyBasePath OPENAPI: base path of the API proxy deployed in this environment; tool calls go
     *                      to the gateway at {@code /{org}/{env}{proxyBasePath}{tool path}}
     * @param remoteUrl     REMOTE: the server's MCP endpoint
     * @param remoteToken   REMOTE: true when calls need a bearer token (ask the token endpoint)
     */
    public record RuntimeMcpServer(String name, String displayName, String description, String type,
                                   String proxyBasePath, List<RuntimeMcpTool> tools, String remoteUrl,
                                   boolean remoteToken, List<String> allowedTools, int timeoutSeconds) {
    }

    public record RuntimeMcpTool(String name, String title, String description, Map<String, Object> inputSchema,
                                 Map<String, Object> annotations, String method, String path,
                                 List<RuntimeHttpParameter> parameters, boolean hasBody, String bodyContentType) {
    }

    public record RuntimeHttpParameter(String name, String in) {
    }

    /** A remote MCP server's current access token ({@code accessToken} null when it needs none). */
    public record RuntimeMcpToken(String accessToken, Instant expiresAt) {
    }
}
