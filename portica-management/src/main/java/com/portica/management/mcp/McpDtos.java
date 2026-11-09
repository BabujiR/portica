package com.portica.management.mcp;

import com.portica.common.validation.ResourceName;
import com.portica.management.ai.AiDtos.ClientAuth;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phase 7 MCP API. */
public final class McpDtos {

    static final String TOOL = "^[A-Za-z0-9_-]{1,64}$";
    static final String SCOPE = "^[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,128}$";

    private McpDtos() {
    }

    /** OPENAPI: tools generated from a deployed API proxy's OpenAPI spec. REMOTE: an MCP server Portica fronts. */
    public enum McpServerType { OPENAPI, REMOTE }

    /**
     * How Portica authenticates to a remote MCP server: none, a static bearer token, or OAuth 2.1 with
     * client credentials (machine to machine) or authorization code with PKCE (an admin signs in once).
     */
    public enum RemoteAuth { NONE, BEARER, OAUTH_CLIENT_CREDENTIALS, OAUTH_AUTHORIZATION_CODE }

    /** Remote server settings as stored (secrets live in encrypted columns, not here). */
    public record RemoteSpec(String url, RemoteAuth auth, String clientId, List<String> scopes,
                             String authorizationServer) {
    }

    public record McpServerSpec(List<String> environments, ClientAuth auth, McpServerType type, String apiProxy,
                                List<String> tools, RemoteSpec remote, int timeoutSeconds) {
    }

    public record RemoteRequest(
            @NotBlank @Size(max = 2048) @Schema(example = "https://mcp.example.com/mcp") String url,
            RemoteAuth auth,
            @Size(max = 4096) @Schema(description = "Static bearer token (auth BEARER, write-only)") String bearerToken,
            @Size(max = 256) String clientId,
            @Size(max = 4096) @Schema(description = "OAuth client secret (write-only); omit for a public client") String clientSecret,
            @Size(max = 30) List<@Pattern(regexp = SCOPE) String> scopes,
            @Size(max = 2048) @Schema(description = "Authorization server issuer; discovered from the MCP server when empty")
            String authorizationServer) {
    }

    public record CreateMcpServerRequest(
            @NotBlank @ResourceName @Size(max = 59) @Schema(example = "petstore") String name,
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Size(max = 50) List<@ResourceName String> environments,
            ClientAuth auth,
            @NotNull McpServerType type,
            @ResourceName @Schema(description = "API proxy whose OpenAPI spec defines the tools (type OPENAPI)") String apiProxy,
            @Size(max = 500) @Schema(description = "Only expose these tools; empty means all")
            List<@Pattern(regexp = TOOL) String> tools,
            @Valid RemoteRequest remote,
            @Min(5) @Max(300) Integer timeoutSeconds) {
    }

    /** Full replacement; secrets left null keep their stored values. */
    public record UpdateMcpServerRequest(
            @Size(max = 128) String displayName,
            @Size(max = 1024) String description,
            @Size(max = 50) List<@ResourceName String> environments,
            ClientAuth auth,
            @ResourceName String apiProxy,
            @Size(max = 500) List<@Pattern(regexp = TOOL) String> tools,
            @Valid RemoteRequest remote,
            @Min(5) @Max(300) Integer timeoutSeconds) {
    }

    public record OAuthStatus(boolean connected, Instant expiresAt, String scope, String issuer,
                              String tokenEndpoint, String authorizationEndpoint, boolean refreshable) {
    }

    public record RemoteResponse(String url, RemoteAuth auth, String clientId, List<String> scopes,
                                 String authorizationServer, boolean secretSet, OAuthStatus oauth) {
    }

    public record McpServerResponse(UUID id, String name, String displayName, String description,
                                    List<String> environments, ClientAuth auth, McpServerType type, String apiProxy,
                                    List<String> tools, RemoteResponse remote, int timeoutSeconds,
                                    @Schema(description = "API proxy name to list in API products to grant apps access")
                                    String gatewayProxy,
                                    @Schema(description = "MCP endpoint path, below /{org}/{env}") String gatewayPath,
                                    Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
    }

    /** A tool as MCP clients will see it, plus how Portica calls it (OpenAPI tools only). */
    public record ToolView(String name, String title, String description, Map<String, Object> inputSchema,
                           Map<String, Object> annotations, OpenApiTools.HttpMapping http) {
    }

    public record AuthorizeResponse(String authorizationUrl, Instant expiresAt) {
    }
}
