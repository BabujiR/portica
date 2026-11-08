package com.portica.common.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A small Model Context Protocol client for the Streamable HTTP transport (protocol 2025-06-18):
 * JSON-RPC 2.0 over POST, answers as {@code application/json} or as a {@code text/event-stream}
 * carrying the response, and the {@code Mcp-Session-Id} header the server may assign at initialization.
 * Used by portica-mcp to reach remote MCP servers, by management to preview their tools, and by tests
 * as a sample MCP client. Not thread-safe across sessions: one instance is one session.
 */
public class McpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String SESSION_HEADER = "Mcp-Session-Id";
    public static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final URI endpoint;
    private final Supplier<Map<String, String>> headers;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;
    private volatile Map<String, Object> serverInfo = Map.of();

    /**
     * @param headers extra headers for every request (e.g. Authorization), asked for each time so
     *                refreshed tokens are picked up
     */
    public McpClient(URI endpoint, Supplier<Map<String, String>> headers, Duration timeout) {
        this(endpoint, headers, timeout, SHARED);
    }

    public McpClient(URI endpoint, Supplier<Map<String, String>> headers, Duration timeout, HttpClient http) {
        this.endpoint = endpoint;
        this.headers = headers == null ? Map::of : headers;
        this.timeout = timeout;
        this.http = http;
    }

    /** Runs the initialization handshake and returns the server's {@code initialize} result. */
    public Map<String, Object> initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "portica", "version", "1.0"));
        Map<String, Object> result = request("initialize", params);
        serverInfo = result;
        notify("notifications/initialized", null);
        return result;
    }

    public Map<String, Object> serverInfo() {
        return serverInfo;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Every tool the server offers, following {@code nextCursor} pages (at most 50 pages). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> result = request("tools/list", params);
            if (result.get("tools") instanceof List<?> list) {
                list.forEach(t -> tools.add((Map<String, Object>) t));
            }
            cursor = result.get("nextCursor") instanceof String c && !c.isBlank() ? c : null;
            if (cursor == null) {
                break;
            }
        }
        return tools;
    }

    /** Calls a tool; the result has {@code content} and possibly {@code isError}/{@code structuredContent}. */
    public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return request("tools/call", params);
    }

    /** Sends a JSON-RPC request and returns its {@code result}; JSON-RPC errors become exceptions. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> request(String method, Map<String, Object> params) {
        long id = ids.incrementAndGet();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        HttpResponse<String> response = post(message);
        JsonNode reply = findResponse(response, id);
        if (reply.has("error")) {
            JsonNode error = reply.get("error");
            throw new McpClientException(response.statusCode(), error.path("code").asInt(),
                    error.path("message").asString("JSON-RPC error"), null);
        }
        JsonNode result = reply.get("result");
        return result == null || result.isNull() ? Map.of() : JSON.convertValue(result, Map.class);
    }

    /** Sends a notification (no response expected; servers answer 202). */
    public void notify(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        post(message);
    }

    private HttpResponse<String> post(Map<String, Object> message) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message), StandardCharsets.UTF_8));
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
            builder.header(PROTOCOL_HEADER, PROTOCOL_VERSION);
        }
        headers.get().forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpClientException(0, null, "MCP server is unreachable: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpClientException(0, null, "Interrupted", null);
        }
        response.headers().firstValue(SESSION_HEADER).ifPresent(s -> sessionId = s);
        int status = response.statusCode();
        if (status == 202 || (status / 100 == 2 && !message.containsKey("id"))) {
            return response;
        }
        if (status / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            String detail = body.length() > 300 ? body.substring(0, 300) + "…" : body;
            throw new McpClientException(status, null, "MCP server returned " + status
                    + (detail.isBlank() ? "" : ": " + detail.strip()),
                    response.headers().firstValue("WWW-Authenticate").orElse(null));
        }
        return response;
    }

    /** The JSON-RPC response with {@code id}: the JSON body, or the matching event of an SSE stream. */
    private static JsonNode findResponse(HttpResponse<String> response, long id) {
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String body = response.body() == null ? "" : response.body();
        if (contentType.startsWith("text/event-stream")) {
            for (String data : sseData(body)) {
                JsonNode node = parse(data);
                if (node != null && node.path("id").asLong(-1) == id && (node.has("result") || node.has("error"))) {
                    return node;
                }
            }
            throw new McpClientException(response.statusCode(), null, "The MCP server's event stream had no response", null);
        }
        JsonNode node = parse(body);
        if (node == null) {
            throw new McpClientException(response.statusCode(), null, "The MCP server's response isn't JSON", null);
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.path("id").asLong(-1) == id) {
                    return item;
                }
            }
        }
        return node;
    }

    /** {@code data:} payloads of each event in an SSE body. */
    static List<String> sseData(String body) {
        List<String> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    events.add(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            }
        }
        if (!data.isEmpty()) {
            events.add(data.toString());
        }
        return events;
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
