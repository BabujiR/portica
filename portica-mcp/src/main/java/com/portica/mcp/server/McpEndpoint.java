package com.portica.mcp.server;

import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.analytics.EventSink;
import com.portica.common.http.PorticaHeaders;
import com.portica.common.mcp.JsonRpc;
import com.portica.common.mcp.McpClient;
import com.portica.common.mcp.McpClientException;
import com.portica.mcp.config.McpGatewayProperties;
import com.portica.mcp.runtime.McpConfigFeed;
import com.portica.mcp.runtime.McpRuntime;
import com.portica.mcp.tools.OpenApiToolInvoker;
import com.portica.mcp.tools.RemoteServers;
import com.portica.mcp.tools.ToolResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Model Context Protocol endpoint of every MCP server Portica serves (Streamable HTTP transport,
 * stateless: each POST carries one JSON-RPC message and gets a JSON answer; there is no server-initiated
 * stream, so GET answers 405 as the spec allows). Supported: {@code initialize}, {@code ping},
 * {@code tools/list}, {@code tools/call} and notifications.
 * <p>
 * The API gateway forwards here after verifying the client's credential ({@code mcp-<name>} proxy), and
 * adds the internal token and the calling app; anything else is refused.
 */
@RestController
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    static final List<String> PROTOCOL_VERSIONS = List.of(McpClient.PROTOCOL_VERSION, "2025-03-26", "2024-11-05");
    static final String PATH = "/internal/mcp/{org}/{env}/{server}";

    private final McpGatewayProperties properties;
    private final McpConfigFeed feed;
    private final OpenApiToolInvoker openApi;
    private final RemoteServers remote;
    private final EventSink events;
    private final JsonMapper json = JsonMapper.builder().build();

    public McpEndpoint(McpGatewayProperties properties, McpConfigFeed feed, OpenApiToolInvoker openApi,
                       RemoteServers remote, EventSink events) {
        this.properties = properties;
        this.feed = feed;
        this.openApi = openApi;
        this.remote = remote;
        this.events = events;
    }

    @GetMapping(PATH)
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @DeleteMapping(PATH)
    public ResponseEntity<Void> endSession() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> post(@PathVariable String org, @PathVariable String env, @PathVariable String server,
                                       @RequestBody(required = false) String body, HttpServletRequest request) {
        if (!viaGateway(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                    .body(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Call the MCP server through the Portica gateway"));
        }
        JsonNode message;
        try {
            message = json.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error"));
        }
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asString(""))
                || !message.path("method").isString()) {
            boolean isResponse = message != null && message.isObject() && (message.has("result") || message.has("error"));
            return isResponse ? ResponseEntity.accepted().build()
                    : ResponseEntity.badRequest().body(JsonRpc.error(id(message), JsonRpc.INVALID_REQUEST,
                    "Expected one JSON-RPC 2.0 request"));
        }
        if (!message.has("id")) {
            return ResponseEntity.accepted().build();    // notification
        }
        Object id = id(message);
        McpRuntime.Server definition = feed.get(org, env).map(c -> c.server(server)).orElse(null);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "MCP server '" + server + "' isn't served in " + org + "/" + env));
        }
        String method = message.path("method").asString();
        JsonNode params = message.path("params");
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(definition, params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools(org, env, definition));
                case "tools/call" -> callTool(org, env, definition, params, request);
                default -> null;
            };
            if (result == null) {
                return ResponseEntity.ok(JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method));
            }
            return ResponseEntity.ok(JsonRpc.result(id, result));
        } catch (RpcException e) {
            return ResponseEntity.ok(JsonRpc.error(id, e.code, e.getMessage()));
        } catch (McpClientException e) {
            Integer code = e.rpcCode();
            return ResponseEntity.ok(JsonRpc.error(id, code != null ? code : JsonRpc.INTERNAL_ERROR,
                    code != null ? e.getMessage() : "The remote MCP server failed: " + e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("MCP {} {} on {}/{}/{} failed", method, id, org, env, server, e);
            return ResponseEntity.ok(JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Internal error"));
        }
    }

    // ------------------------------------------------------------------ methods

    private Map<String, Object> initialize(McpRuntime.Server server, JsonNode params) {
        String requested = params.path("protocolVersion").asString("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.getFirst());
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "portica-" + server.name());
        info.put("title", server.displayName() == null ? server.name() : server.displayName());
        info.put("version", "1.0.0");
        result.put("serverInfo", info);
        if (server.description() != null && !server.description().isBlank()) {
            result.put("instructions", server.description());
        }
        return result;
    }

    private List<Map<String, Object>> tools(String org, String env, McpRuntime.Server server) {
        if (server.remote()) {
            return remote.tools(org, env, server);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpRuntime.Tool t : server.tools() == null ? List.<McpRuntime.Tool>of() : server.tools()) {
            if (!server.allows(t.name())) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            if (t.title() != null) {
                tool.put("title", t.title());
            }
            tool.put("description", t.description() == null ? "" : t.description());
            tool.put("inputSchema", t.inputSchema() == null ? Map.of("type", "object") : t.inputSchema());
            if (t.annotations() != null && !t.annotations().isEmpty()) {
                tool.put("annotations", t.annotations());
            }
            out.add(tool);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String org, String env, McpRuntime.Server server, JsonNode params,
                                         HttpServletRequest request) {
        String name = params.path("name").asString("");
        if (name.isBlank()) {
            throw new RpcException(JsonRpc.INVALID_PARAMS, "params.name is required");
        }
        JsonNode argsNode = params.path("arguments");
        if (!argsNode.isMissingNode() && !argsNode.isNull() && !argsNode.isObject()) {
            throw new RpcException(JsonRpc.INVALID_PARAMS, "params.arguments must be an object");
        }
        Map<String, Object> arguments = argsNode.isObject() ? json.convertValue(argsNode, Map.class) : Map.of();
        if (!server.allows(name)) {
            throw new RpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + name);
        }
        String requestId = header(request, PorticaHeaders.REQUEST_ID);
        if (requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        long start = System.nanoTime();
        Instant startedAt = Instant.now();
        ToolResult result = null;
        String error = "";
        try {
            if (server.remote()) {
                result = remote.call(org, env, server, name, arguments);
            } else {
                McpRuntime.Tool tool = server.tools() == null ? null
                        : server.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
                if (tool == null) {
                    throw new RpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + name);
                }
                result = openApi.call(org, env, server, tool, arguments, callerHeaders(request), requestId);
            }
            if (result.isError()) {
                error = "tool_error";
            }
            return result.result();
        } catch (RpcException | McpClientException e) {
            error = e instanceof RpcException ? "invalid_params" : "upstream_error";
            result = new ToolResult(Map.of(), true, 0);
            throw e;
        } finally {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_time", startedAt.toString());
            event.put("request_id", requestId);
            event.put("organization", org);
            event.put("environment", env);
            event.put("mcp_server", server.name());
            event.put("server_type", server.remote() ? "REMOTE" : "OPENAPI");
            event.put("tool", name);
            event.put("app", header(request, PorticaHeaders.APP));
            event.put("developer", header(request, PorticaHeaders.DEVELOPER));
            event.put("is_error", error.isEmpty() ? 0 : 1);
            event.put("upstream_status", result == null ? 0 : result.upstreamStatus());
            event.put("total_ms", (System.nanoTime() - start) / 1_000_000);
            event.put("error", error);
            try {
                events.publish(AnalyticsTopics.MCP_TOOL_CALLS, requestId, event);
            } catch (RuntimeException ignored) {
                // analytics never fails a call
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean viaGateway(HttpServletRequest request) {
        String expected = properties.internalToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String given = request.getHeader(PorticaHeaders.INTERNAL_TOKEN);
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> callerHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    private static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return v == null ? "" : v.strip();
    }

    private static Object id(JsonNode message) {
        if (message == null || !message.has("id")) {
            return null;
        }
        JsonNode id = message.get("id");
        return id.isNumber() ? id.numberValue() : id.isString() ? id.asString() : null;
    }

    /** A JSON-RPC error answered to the client. */
    static final class RpcException extends RuntimeException {
        final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
