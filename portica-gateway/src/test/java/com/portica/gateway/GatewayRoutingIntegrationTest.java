package com.portica.gateway;

import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real gateway (Netty) and a tiny JDK HTTP backend, then changes the gateway's
 * configuration the way the synchronizer does, verifying routes appear, swap and disappear live.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"portica.gateway.sync.enabled=false", "portica.gateway.policy.store=memory"})
class GatewayRoutingIntegrationTest {

    private static HttpServer backend;
    private static final Map<String, String> lastRequest = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    int gatewayPort;

    @Autowired
    RuntimeConfigRegistry registry;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            lastRequest.put("path", exchange.getRequestURI().getRawPath());
            lastRequest.put("query", String.valueOf(exchange.getRequestURI().getRawQuery()));
            lastRequest.put("proxy", String.valueOf(exchange.getRequestHeaders().getFirst("X-Portica-Proxy")));
            lastRequest.put("revision", String.valueOf(exchange.getRequestHeaders().getFirst("X-Portica-Revision")));
            lastRequest.put("org", String.valueOf(exchange.getRequestHeaders().getFirst("X-Portica-Org")));
            lastRequest.put("requestId", String.valueOf(exchange.getRequestHeaders().getFirst("X-Portica-Request-Id")));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Test
    void deploymentsChangeRoutesWithoutRestart() throws Exception {
        String target = "http://127.0.0.1:" + backend.getAddress().getPort();

        // Nothing deployed: 404. Test classes with the same settings share one Spring context (and so one
        // registry), so start from a clean environment.
        registry.remove(List.of("acme/dev"));
        awaitStatus("/acme/dev/pets/1", 404);

        // Deploy revision 1 (no target path).
        registry.apply(env(1, new RuntimeProxy("pets", 1, "/pets", target, "", 2_000, 10_000)));
        HttpResponse<String> first = awaitStatus("/acme/dev/pets/1?color=red", 200);
        assertThat(lastRequest).containsEntry("path", "/1")
                .containsEntry("query", "color=red")
                .containsEntry("proxy", "pets")
                .containsEntry("revision", "1")
                .containsEntry("org", "acme");
        assertThat(first.headers().firstValue("X-Portica-Request-Id")).isPresent();

        // Clients cannot spoof gateway-owned headers.
        get("/acme/dev/pets/1", Map.of("X-Portica-Proxy", "evil", "X-Portica-Request-Id", "client-req-0001"));
        assertThat(lastRequest).containsEntry("proxy", "pets").containsEntry("requestId", "client-req-0001");

        // Swap to revision 2 with a target path: same URL now lands on /v2/... (zero-downtime swap).
        registry.apply(env(2, new RuntimeProxy("pets", 2, "/pets", target, "/v2", 2_000, 10_000)));
        awaitBackend("/acme/dev/pets/1", r -> "/v2/1".equals(lastRequest.get("path")));
        assertThat(lastRequest).containsEntry("revision", "2");

        // Undeploy everything in the environment: 404 again.
        registry.apply(env(3));
        awaitStatus("/acme/dev/pets/1", 404);
    }

    private static RuntimeEnvironmentConfig env(long version, RuntimeProxy... proxies) {
        return new RuntimeEnvironmentConfig("acme", "dev", version, Instant.now(), List.of(proxies));
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Route refresh is asynchronous; poll briefly until the new table is live. */
    private HttpResponse<String> awaitStatus(String path, int status) throws Exception {
        return awaitBackend(path, r -> r.statusCode() == status);
    }

    private HttpResponse<String> awaitBackend(String path, Predicate<HttpResponse<String>> condition) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        HttpResponse<String> response = get(path, Map.of());
        while (!condition.test(response) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            response = get(path, Map.of());
        }
        assertThat(condition.test(response)).as("condition met for %s (last status %d)", path, response.statusCode())
                .isTrue();
        return response;
    }
}
