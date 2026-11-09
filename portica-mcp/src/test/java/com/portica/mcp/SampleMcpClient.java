package com.portica.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A sample MCP client, as an AI agent would use a Portica MCP server: initialize, list tools, call one.
 * Speaks the Streamable HTTP transport with plain JDK HTTP. Run it against a gateway:
 * <pre>
 *   java SampleMcpClient http://localhost:8081/acme/dev/mcp/petstore dev-acme-key-123 get_pet '{"petId":1}'
 * </pre>
 */
public class SampleMcpClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicLong ids = new AtomicLong();
    private String sessionId;
    private String protocolVersion;

    public SampleMcpClient(URI endpoint, Map<String, String> headers) {
        this.endpoint = endpoint;
        this.headers = headers;
    }

    public JsonNode initialize() {
        JsonNode result = request("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "sample-client", "version", "1.0")));
        protocolVersion = result.path("protocolVersion").asString();
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return result;
    }

    public JsonNode listTools() {
        return request("tools/list", Map.of()).path("tools");
    }

    public JsonNode callTool(String name, Map<String, Object> arguments) {
        return request("tools/call", Map.of("name", name, "arguments", arguments));
    }

    /** The JSON-RPC result; a JSON-RPC error throws. */
    public JsonNode request(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", ids.incrementAndGet());
        message.put("method", method);
        message.put("params", params);
        HttpResponse<String> response = send(message);
        JsonNode reply = JSON.readTree(response.body());
        if (reply.has("error")) {
            throw new RpcError(reply.path("error").path("code").asInt(), reply.path("error").path("message").asString());
        }
        return reply.path("result");
    }

    public HttpResponse<String> send(Map<String, Object> message) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message)));
        headers.forEach(request::header);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("MCP request failed: " + e.getMessage(), e);
        }
    }

    public static class RpcError extends RuntimeException {
        public final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: SampleMcpClient <mcp-url> <api-key> [tool] [json-arguments]");
            return;
        }
        SampleMcpClient client = new SampleMcpClient(URI.create(args[0]), Map.of("x-api-key", args[1]));
        System.out.println("server: " + client.initialize().path("serverInfo"));
        for (JsonNode tool : client.listTools()) {
            System.out.println("- " + tool.path("name").asString() + ": " + tool.path("description").asString());
        }
        if (args.length >= 3) {
            Map<String, Object> arguments = args.length >= 4 ? JSON.readValue(args[3], Map.class) : Map.of();
            System.out.println(client.callTool(args[2], arguments).toPrettyString());
        }
    }
}
