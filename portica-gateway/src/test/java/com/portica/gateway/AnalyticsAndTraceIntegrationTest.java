package com.portica.gateway;

import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.gateway.analytics.EventPublisher;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.trace.TraceSession;
import com.portica.gateway.trace.TraceSessionRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every proxied call produces one analytics event, and calls matching an active trace session also
 * produce a trace transaction with each policy step, the target call and masked credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.gateway.sync.enabled=false",
        "portica.gateway.policy.store=memory",
        "portica.gateway.analytics.enabled=false",
        "portica.gateway.analytics.gateway-id=gw-test"})
class AnalyticsAndTraceIntegrationTest {

    record Published(String topic, String key, Map<String, Object> event) {
    }

    static final List<Published> published = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Capture {
        @Bean
        @Primary
        EventPublisher capturingPublisher() {
            return (topic, key, event) -> published.add(new Published(topic, key, event));
        }
    }

    private static HttpServer backend;

    @Value("${local.server.port}")
    int gatewayPort;

    @Autowired
    RuntimeConfigRegistry registry;

    @Autowired
    TraceSessionRegistry traces;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/missing") ? 404 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @BeforeEach
    void deploy() throws Exception {
        String target = "http://127.0.0.1:" + backend.getAddress().getPort();
        List<PolicyDefinition> policies = List.of(
                policy("tag", "AssignMessage", Map.of("setHeaders", Map.of("X-Tag", "yes"))),
                policy("block", "RaiseFault", Map.of("statusCode", 403, "body", "blocked")),
                policy("brand", "AssignMessage", Map.of("target", "response", "setHeaders", Map.of("X-Brand", "portica"))));
        ProxyFlows flows = new ProxyFlows(
                new FlowPair(List.of(new FlowStep("tag", null), new FlowStep("block", "request.header.x-block != null")),
                        List.of(new FlowStep("brand", null))),
                List.of(), null, List.of(), null);
        RuntimeProxy proxy = new RuntimeProxy("orders", 3, "/orders", target, "/v1", 2_000, 10_000, policies, flows);
        registry.apply(new RuntimeEnvironmentConfig("globex", "test", 1, Instant.now(), List.of(proxy), List.of()));
        Instant deadline = Instant.now().plusSeconds(10);
        while (send("/globex/test/orders/1", Map.of()).statusCode() == 404 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        Thread.sleep(200); // let the warm-up calls' events land before clearing
        published.clear();
    }

    @AfterEach
    void cleanUp() {
        traces.update(List.of());
        registry.remove(List.of("globex/test"));
    }

    @Test
    void everyCallProducesOneAnalyticsEvent() throws Exception {
        HttpResponse<String> ok = send("/globex/test/orders/42?x=1", Map.of());
        assertThat(ok.statusCode()).isEqualTo(200);
        HttpResponse<String> blocked = send("/globex/test/orders/42", Map.of("x-block", "1"));
        assertThat(blocked.statusCode()).isEqualTo(403);

        List<Map<String, Object>> events = await(AnalyticsTopics.API_EVENTS, 2);
        Map<String, Object> first = events.get(0);
        assertThat(first).containsEntry("organization", "globex").containsEntry("environment", "test")
                .containsEntry("api_proxy", "orders").containsEntry("revision", 3)
                .containsEntry("method", "GET").containsEntry("path", "/orders/42")
                .containsEntry("status", 200).containsEntry("target_called", 1)
                .containsEntry("fault", "").containsEntry("gateway", "gw-test");
        assertThat(first.get("request_id")).isEqualTo(ok.headers().firstValue("X-Portica-Request-Id").orElseThrow());

        Map<String, Object> second = events.get(1);
        assertThat(second).containsEntry("status", 403).containsEntry("target_called", 0)
                .containsEntry("target_ms", 0L).containsEntry("fault", "RaiseFault");
        assertThat(published).noneMatch(p -> p.topic().equals(AnalyticsTopics.TRACE_TRANSACTIONS));
    }

    @Test
    void tracedCallsRecordStepsTargetAndMaskSecrets() throws Exception {
        traces.update(List.of(new TraceSession("sess-1", "globex", "test", "orders", "X-Debug", "on", null, 5,
                Instant.now().plusSeconds(60))));

        send("/globex/test/orders/7", Map.of());                                    // no filter header: not traced
        send("/globex/test/orders/missing?apikey=s3cret", Map.of("X-Debug", "on", "Authorization", "Bearer abc"));

        List<Map<String, Object>> traced = await(AnalyticsTopics.TRACE_TRANSACTIONS, 1);
        assertThat(await(AnalyticsTopics.API_EVENTS, 2)).hasSize(2);
        Map<String, Object> row = traced.get(0);
        assertThat(row).containsEntry("session_id", "sess-1").containsEntry("api_proxy", "orders").containsEntry("status", 404);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) row.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) payload.get("request");
        assertThat((String) request.get("uri")).contains("apikey=*****").doesNotContain("s3cret");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) request.get("headers");
        assertThat(headers.entrySet()).anyMatch(e -> e.getKey().equalsIgnoreCase("authorization") && e.getValue().equals("*****"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) payload.get("steps");
        assertThat(steps).extracting(s -> s.get("policy") + ":" + s.get("outcome"))
                .containsExactly("tag:executed", "block:skipped", "brand:executed");
        assertThat(steps.get(0)).containsEntry("flow", "PreFlow request");
        assertThat(steps.get(1)).containsEntry("condition", "request.header.x-block != null");

        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) payload.get("target");
        assertThat(target).containsEntry("status", 404);
        assertThat((String) target.get("url")).endsWith("/v1/missing");
    }

    @Test
    void sessionsStopCapturingAfterMaxTransactions() throws Exception {
        traces.update(List.of(new TraceSession("sess-2", "globex", "test", "orders", null, null, null, 2,
                Instant.now().plusSeconds(60))));
        for (int i = 0; i < 4; i++) {
            send("/globex/test/orders/" + i, Map.of());
        }
        await(AnalyticsTopics.API_EVENTS, 4);
        assertThat(published).filteredOn(p -> p.topic().equals(AnalyticsTopics.TRACE_TRANSACTIONS)).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> await(String topic, int count) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (true) {
            List<Map<String, Object>> events = published.stream().filter(p -> p.topic().equals(topic))
                    .map(Published::event).toList();
            if (events.size() >= count || Instant.now().isAfter(deadline)) {
                assertThat(events).as(topic).hasSize(count);
                return events;
            }
            Thread.sleep(25);
        }
    }

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, new LinkedHashMap<>(config));
    }

    private HttpResponse<String> send(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
