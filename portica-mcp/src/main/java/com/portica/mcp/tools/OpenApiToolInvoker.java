package com.portica.mcp.tools;

import com.portica.common.http.PorticaHeaders;
import com.portica.mcp.config.McpGatewayProperties;
import com.portica.mcp.runtime.McpRuntime;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Calls an OpenAPI-generated tool by sending the operation's HTTP request to the API proxy <em>through
 * the gateway</em> ({@code {gateway}/{org}/{env}{basePath}{path}}), with the MCP client's own credential.
 * So the proxy's policies (key or token verification, product grants, quotas, ...) and the analytics
 * pipeline apply to every tool call exactly as to any other API call.
 */
@Component
public class OpenApiToolInvoker {

    /** Request headers carrying the caller's credential to the API proxy. */
    static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "x-api-key", "apikey");
    /** Headers a tool argument may not set (transport, credentials and Portica's own). */
    private static final Set<String> FORBIDDEN = Set.of("host", "content-length", "connection", "transfer-encoding",
            "authorization", "x-api-key", "apikey", "cookie", "upgrade", "expect");

    private final McpGatewayProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public OpenApiToolInvoker(McpGatewayProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public ToolResult call(String org, String env, McpRuntime.Server server, McpRuntime.Tool tool,
                           Map<String, Object> arguments, Map<String, String> callerHeaders, String requestId) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String path = tool.path();
        StringJoiner query = new StringJoiner("&");
        Map<String, String> headers = new LinkedHashMap<>();
        for (McpRuntime.HttpParameter p : tool.parameters() == null ? List.<McpRuntime.HttpParameter>of() : tool.parameters()) {
            Object value = args.get(p.name());
            if (value == null) {
                if ("path".equals(p.in())) {
                    return ToolResult.text("Missing required argument '" + p.name() + "'", true, 0);
                }
                continue;
            }
            switch (p.in()) {
                case "path" -> path = path.replace("{" + p.name() + "}", URLEncoder.encode(scalar(value), StandardCharsets.UTF_8)
                        .replace("+", "%20"));
                case "query" -> {
                    for (Object v : value instanceof List<?> list ? list : List.of(value)) {
                        query.add(URLEncoder.encode(p.name(), StandardCharsets.UTF_8) + "="
                                + URLEncoder.encode(scalar(v), StandardCharsets.UTF_8));
                    }
                }
                case "header" -> {
                    if (!FORBIDDEN.contains(p.name().toLowerCase(Locale.ROOT))
                            && !p.name().toLowerCase(Locale.ROOT).startsWith("x-portica-")) {
                        headers.put(p.name(), scalar(value).replaceAll("[\\r\\n]", " "));
                    }
                }
                default -> {
                    // cookie parameters are not supported
                }
            }
        }
        String url = properties.gatewayUrl() + "/" + org + "/" + env + server.proxyBasePath() + path
                + (query.length() == 0 ? "" : "?" + query);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (tool.hasBody() && args.containsKey("body")) {
            Object b = args.get("body");
            String text = b instanceof String s && !(tool.bodyContentType() != null && tool.bodyContentType().contains("json"))
                    ? s : json.writeValueAsString(b);
            body = HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
            headers.put("Content-Type", tool.bodyContentType() == null ? "application/json" : tool.bodyContentType());
        }
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, server.timeoutSeconds())))
                    .method(tool.method(), body)
                    .header("Accept", "application/json, */*;q=0.5")
                    .header(PorticaHeaders.REQUEST_ID, requestId);
        } catch (IllegalArgumentException e) {
            return ToolResult.text("Invalid argument for the request URL: " + e.getMessage(), true, 0);
        }
        headers.forEach(request::header);
        callerHeaders.forEach((name, value) -> {
            if (CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                request.header(name, value);
            }
        });
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            return ToolResult.text("The API didn't answer within " + server.timeoutSeconds() + " seconds", true, 504);
        } catch (IOException e) {
            return ToolResult.text("The API couldn't be reached: " + e.getMessage(), true, 502);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.text("Interrupted", true, 0);
        }
        return result(response);
    }

    private ToolResult result(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean truncated = bytes.length > properties.maxResultBytes();
        String text = new String(bytes, 0, Math.min(bytes.length, properties.maxResultBytes()), StandardCharsets.UTF_8);
        boolean error = status >= 400;
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        String shown = text.isEmpty() ? "HTTP " + status + " (no content)" : text + (truncated ? "\n… (truncated)" : "");
        if (error) {
            shown = "HTTP " + status + ": " + shown;
        }
        content.add(Map.of("type", "text", "text", shown));
        result.put("content", content);
        if (!error && !truncated && contentType.contains("json")) {
            try {
                JsonNode node = json.readTree(text);
                if (node.isObject()) {
                    result.put("structuredContent", json.convertValue(node, Map.class));
                }
            } catch (RuntimeException ignored) {
                // not JSON after all
            }
        }
        result.put("isError", error);
        return new ToolResult(result, error, status);
    }

    private String scalar(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return json.writeValueAsString(value);
        }
        return String.valueOf(value);
    }
}
