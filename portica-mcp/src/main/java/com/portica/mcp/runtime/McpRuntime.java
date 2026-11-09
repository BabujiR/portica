package com.portica.mcp.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** portica-mcp's copy of management's runtime MCP feed (keep field names in step; unknown ones are ignored). */
public final class McpRuntime {

    private McpRuntime() {
    }

    public record Config(String organization, String environment, long configVersion, List<Server> servers) {

        public Server server(String name) {
            return servers == null ? null : servers.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * {@code type}: OPENAPI (tools below, called through the gateway at {@code proxyBasePath}) or REMOTE
     * ({@code remoteUrl}, with a token from management when {@code remoteToken}). {@code allowedTools}
     * empty means all.
     */
    public record Server(String name, String displayName, String description, String type, String proxyBasePath,
                         List<Tool> tools, String remoteUrl, boolean remoteToken, List<String> allowedTools,
                         int timeoutSeconds) {

        public boolean remote() {
            return "REMOTE".equals(type);
        }

        public boolean allows(String tool) {
            return allowedTools == null || allowedTools.isEmpty() || allowedTools.contains(tool);
        }
    }

    public record Tool(String name, String title, String description, Map<String, Object> inputSchema,
                       Map<String, Object> annotations, String method, String path, List<HttpParameter> parameters,
                       boolean hasBody, String bodyContentType) {
    }

    /** {@code in}: path, query or header. */
    public record HttpParameter(String name, String in) {
    }

    public record Token(String accessToken, Instant expiresAt) {
    }
}
