package com.portica.mcp.tools;

import com.portica.common.mcp.McpClient;
import com.portica.common.mcp.McpClientException;
import com.portica.mcp.config.McpGatewayProperties;
import com.portica.mcp.runtime.McpRuntime;
import com.portica.mcp.runtime.RemoteTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to remote MCP servers on behalf of clients. One upstream session per server is kept and
 * re-established when the server drops it; the upstream credential (a static or OAuth access token held
 * by the management plane) is added here and the client's own credential is never forwarded.
 */
@Component
public class RemoteServers {

    private static final Logger log = LoggerFactory.getLogger(RemoteServers.class);

    private record Session(String url, McpClient client) {
    }

    private record CachedTools(List<Map<String, Object>> tools, Instant until) {
    }

    private final McpGatewayProperties properties;
    private final RemoteTokens tokens;
    private final HttpClient http;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, CachedTools> toolLists = new ConcurrentHashMap<>();

    public RemoteServers(McpGatewayProperties properties, RemoteTokens tokens, HttpClient http) {
        this.properties = properties;
        this.tokens = tokens;
        this.http = http;
    }

    /** The server's tools (cached for {@code portica.mcp.tool-list-cache}), without the ones not allowed. */
    public List<Map<String, Object>> tools(String org, String env, McpRuntime.Server server) {
        String key = key(org, env, server);
        CachedTools cached = toolLists.get(key);
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.tools();
        }
        List<Map<String, Object>> tools = withSession(org, env, server, McpClient::listTools).stream()
                .filter(t -> server.allows(String.valueOf(t.get("name"))))
                .toList();
        toolLists.put(key, new CachedTools(tools, Instant.now().plus(properties.toolListCache())));
        return tools;
    }

    public ToolResult call(String org, String env, McpRuntime.Server server, String tool, Map<String, Object> arguments) {
        try {
            Map<String, Object> result = withSession(org, env, server, c -> c.callTool(tool, arguments));
            return new ToolResult(result, Boolean.TRUE.equals(result.get("isError")), 200);
        } catch (McpClientException e) {
            if (e.rpcCode() != null) {
                throw e;    // a JSON-RPC error (e.g. unknown tool): passed on as such
            }
            log.warn("Remote MCP server {} failed a call to {}: {}", server.name(), tool, e.getMessage());
            int status = e.httpStatus() == 0 ? 502 : e.httpStatus();
            return ToolResult.text("The remote MCP server failed: " + e.getMessage(), true, status);
        }
    }

    private <T> T withSession(String org, String env, McpRuntime.Server server, java.util.function.Function<McpClient, T> action) {
        String key = key(org, env, server);
        Session session = session(org, env, server, key);
        try {
            return action.apply(session.client());
        } catch (McpClientException e) {
            // expired session (404), or a token the server no longer accepts (401): start over once
            if (e.httpStatus() == 404 || e.httpStatus() == 401 || e.httpStatus() == 400 && e.rpcCode() == null) {
                if (e.httpStatus() == 401) {
                    tokens.invalidate(org, server.name());
                }
                sessions.remove(key);
                return action.apply(session(org, env, server, key).client());
            }
            throw e;
        }
    }

    private Session session(String org, String env, McpRuntime.Server server, String key) {
        Session existing = sessions.get(key);
        if (existing != null && existing.url().equals(server.remoteUrl())) {
            return existing;
        }
        McpClient client = new McpClient(URI.create(server.remoteUrl()), () -> server.remoteToken()
                ? Map.of("Authorization", "Bearer " + tokens.token(org, server.name()))
                : Map.of(), Duration.ofSeconds(Math.max(5, server.timeoutSeconds())), http);
        client.initialize();
        Session session = new Session(server.remoteUrl(), client);
        sessions.put(key, session);
        return session;
    }

    private static String key(String org, String env, McpRuntime.Server server) {
        return org + "/" + env + "/" + server.name();
    }
}
