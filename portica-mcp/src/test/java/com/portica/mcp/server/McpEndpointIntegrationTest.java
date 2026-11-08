package com.portica.mcp.server;

import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.analytics.EventSink;
import com.portica.common.http.PorticaHeaders;
import com.portica.mcp.SampleMcpClient;
import com.portica.mcp.runtime.McpConfigFeed;
import com.portica.mcp.runtime.McpRuntime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * portica-mcp driven by a sample MCP client, with stand-ins for the API gateway (OpenAPI tools are
 * called through it), a remote MCP server that requires a bearer token, and the management plane that
 * hands out that token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.mcp.sync.enabled=false",
        "portica.mcp.analytics.enabled=false",
        "portica.mcp.internal-token=test-internal",
        "portica.mcp.auth.token-uri="})
class McpEndpointIntegrationTest {

    record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) {
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    static final List<Recorded> gatewayCalls = new CopyOnWriteArrayList<>();
    static final List<Recorded> upstreamCalls = new CopyOnWriteArrayList<>();
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static HttpServer stubs;

    @TestConfiguration
    static class Capture {
        @Bean
        @Primary
        EventSink capturingSink() {
            return (topic, key, event) -> {
                if (topic.equals(AnalyticsTopics.MCP_TOOL_CALLS)) {
                    events.add(event);
                }
            };
        }
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) throws IOException {
        startStubs();
        String base = "http://127.0.0.1:" + stubs.getAddress().getPort();
        registry.add("portica.mcp.gateway-url", () -> base + "/gw");
        registry.add("portica.mcp.management-url", () -> base + "/mgmt");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    McpConfigFeed feed;

    @BeforeEach
    void configure() {
        events.clear();
        gatewayCalls.clear();
        upstreamCalls.clear();
        String base = "http://127.0.0.1:" + stubs.getAddress().getPort();
        McpRuntime.Tool getPet = new McpRuntime.Tool("get_pet", "Get a pet", "Find a pet by id",
                Map.of("type", "object", "properties", Map.of("petId", Map.of("type", "integer"),
                        "verbose", Map.of("type", "boolean")), "required", List.of("petId")),
                Map.of("readOnlyHint", true), "GET", "/pets/{petId}",
                List.of(new McpRuntime.HttpParameter("petId", "path"), new McpRuntime.HttpParameter("verbose", "query")),
                false, null);
        McpRuntime.Tool addPet = new McpRuntime.Tool("add_pet", null, "Add a pet",
                Map.of("type", "object", "properties", Map.of("body", Map.of("type", "object"))),
                Map.of(), "POST", "/pets", List.of(), true, "application/json");
        feed.put(new McpRuntime.Config("acme", "dev", 1, List.of(
                new McpRuntime.Server("petstore", "Pet Store", "Pets API as tools", "OPENAPI", "/pets",
                        List.of(getPet, addPet), null, false, List.of(), 10),
                new McpRuntime.Server("github", "GitHub", null, "REMOTE", null, List.of(), base + "/upstream/mcp",
                        true, List.of("search"), 10))));
    }

    @AfterAll
    static void stopStubs() {
        stubs.stop(0);
    }

    private SampleMcpClient client(String server) {
        return new SampleMcpClient(URI.create("http://localhost:" + port + "/internal/mcp/acme/dev/" + server), Map.of(
                PorticaHeaders.INTERNAL_TOKEN, "test-internal",
                PorticaHeaders.APP, "agent-app",
                PorticaHeaders.REQUEST_ID, "req-1",
                "x-api-key", "caller-key"));
    }

    @Test
    void listsToolsGeneratedFromOpenApi() {
        SampleMcpClient client = client("petstore");
        JsonNode init = client.initialize();
        assertThat(init.path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.path("serverInfo").path("title").asString()).isEqualTo("Pet Store");
        assertThat(init.path("capabilities").has("tools")).isTrue();
        JsonNode tools = client.listTools();
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).path("name").asString()).isEqualTo("get_pet");
        assertThat(tools.get(0).path("inputSchema").path("required").get(0).asString()).isEqualTo("petId");
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean()).isTrue();
    }

    @Test
    void openApiToolCallsGoThroughTheGatewayWithTheCallersCredential() {
        SampleMcpClient client = client("petstore");
        client.initialize();
        JsonNode result = client.callTool("get_pet", Map.of("petId", 42, "verbose", true));
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("name").asString()).isEqualTo("Rex");
        assertThat(result.path("content").get(0).path("text").asString()).contains("Rex");

        Recorded call = gatewayCalls.getFirst();
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/gw/acme/dev/pets/pets/42");
        assertThat(call.query()).isEqualTo("verbose=true");
        assertThat(call.headers().get("X-api-key")).containsExactly("caller-key");
        assertThat(call.headers().get("X-portica-request-id")).containsExactly("req-1");
        assertThat(call.headers()).doesNotContainKey("X-portica-internal-token");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).containsEntry("mcp_server", "petstore").containsEntry("tool", "get_pet")
                .containsEntry("server_type", "OPENAPI").containsEntry("app", "agent-app")
                .containsEntry("upstream_status", 200).containsEntry("is_error", 0);
    }

    @Test
    void sendsBodiesAndReportsApiErrorsAsToolErrors() {
        SampleMcpClient client = client("petstore");
        client.initialize();
        JsonNode created = client.callTool("add_pet", Map.of("body", Map.of("name", "Tom")));
        assertThat(created.path("isError").asBoolean()).isFalse();
        assertThat(gatewayCalls.getFirst().body()).isEqualTo("{\"name\":\"Tom\"}");
        assertThat(gatewayCalls.getFirst().headers().get("Content-type")).containsExactly("application/json");

        JsonNode missing = client.callTool("get_pet", Map.of("petId", 404));
        assertThat(missing.path("isError").asBoolean()).isTrue();
        assertThat(missing.path("content").get(0).path("text").asString()).startsWith("HTTP 404");
        assertThat(events.getLast()).containsEntry("is_error", 1).containsEntry("upstream_status", 404);

        assertThatThrownBy(() -> client.callTool("no_such_tool", Map.of()))
                .isInstanceOfSatisfying(SampleMcpClient.RpcError.class, e -> assertThat(e.code).isEqualTo(-32602));
    }

    @Test
    void proxiesRemoteServersWithTheUpstreamToken() {
        SampleMcpClient client = client("github");
        client.initialize();
        JsonNode tools = client.listTools();
        assertThat(tools).hasSize(1);   // delete_repo isn't allowed
        assertThat(tools.get(0).path("name").asString()).isEqualTo("search");

        JsonNode result = client.callTool("search", Map.of("q", "portica"));
        assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("found: portica");
        assertThat(upstreamCalls).allSatisfy(c -> {
            assertThat(c.headers().get("Authorization")).containsExactly("Bearer upstream-token-1");
            assertThat(c.headers()).doesNotContainKey("X-api-key");
        });
        assertThat(upstreamCalls.stream().filter(c -> c.body().contains("tools/call")).findFirst().orElseThrow()
                .headers().get("Mcp-session-id")).containsExactly("upstream-session");
        assertThat(tokenRequests.get()).as("token cached").isEqualTo(1);
        assertThat(events.getLast()).containsEntry("server_type", "REMOTE").containsEntry("tool", "search");

        assertThatThrownBy(() -> client.callTool("delete_repo", Map.of()))
                .isInstanceOfSatisfying(SampleMcpClient.RpcError.class, e -> assertThat(e.code).isEqualTo(-32602));
    }

    @Test
    void speaksTheTransportCorrectly() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/internal/mcp/acme/dev/petstore");
        HttpResponse<String> notGateway = http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(notGateway.statusCode()).isEqualTo(401);

        HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri).header(PorticaHeaders.INTERNAL_TOKEN, "test-internal")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);

        SampleMcpClient client = client("petstore");
        assertThat(client.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode()).isEqualTo(202);
        assertThat(client.request("ping", Map.of()).isEmpty()).isTrue();
        assertThatThrownBy(() -> client.request("resources/list", Map.of()))
                .isInstanceOfSatisfying(SampleMcpClient.RpcError.class, e -> assertThat(e.code).isEqualTo(-32601));
        assertThatThrownBy(() -> client("nope").request("ping", Map.of())).isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------ stand-ins

    static void startStubs() throws IOException {
        if (stubs != null) {
            return;
        }
        stubs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // the API gateway in front of the pets API
        stubs.createContext("/gw/", ex -> {
            Recorded r = record(ex);
            gatewayCalls.add(r);
            if (r.path().endsWith("/404")) {
                send(ex, 404, "{\"detail\":\"Pet not found\"}");
            } else if (r.method().equals("POST")) {
                send(ex, 201, "{\"id\":7,\"name\":\"Tom\"}");
            } else {
                send(ex, 200, "{\"id\":42,\"name\":\"Rex\"}");
            }
        });
        // management: hands out the remote server's access token
        stubs.createContext("/mgmt/internal/v1/runtime/organizations/acme/mcp-servers/github/token", ex -> {
            record(ex);
            tokenRequests.incrementAndGet();
            send(ex, 200, "{\"accessToken\":\"upstream-token-1\",\"expiresAt\":\"" + Instant.now().plusSeconds(3600) + "\"}");
        });
        // a remote MCP server that requires a bearer token
        stubs.createContext("/upstream/mcp", ex -> {
            Recorded r = record(ex);
            upstreamCalls.add(r);
            if (!"Bearer upstream-token-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(ex, 401, "{}");
                return;
            }
            JsonNode msg = JSON.readTree(r.body());
            if (!msg.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            Object id = msg.path("id").asLong();
            String result = switch (msg.path("method").asString()) {
                case "initialize" -> {
                    ex.getResponseHeaders().set("Mcp-Session-Id", "upstream-session");
                    yield "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"gh\",\"version\":\"1\"}}";
                }
                case "tools/list" -> "{\"tools\":[{\"name\":\"search\",\"description\":\"Search\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"delete_repo\",\"description\":\"Delete\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"found: "
                        + msg.path("params").path("arguments").path("q").asString() + "\"}]}";
                default -> null;
            };
            String reply = result == null
                    ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"nope\"}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
            // answer as an SSE stream, as many MCP servers do
            send(ex, 200, "text/event-stream", "event: message\ndata: " + reply + "\n\n");
        });
        stubs.start();
    }

    private static Recorded record(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(),
                Map.copyOf(ex.getRequestHeaders()), body);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json", body);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
