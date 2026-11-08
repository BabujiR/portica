package com.portica.gateway;

import com.portica.common.policy.ConditionalFlow;
import com.portica.common.policy.FaultRule;
import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeSharedFlow;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A full policy chain through the real gateway: CORS, VerifyAPIKey, conditional Quota, request
 * AssignMessage, FlowCallout into a shared flow, a conditional flow converting JSON to XML, a
 * header-only response AssignMessage and a fault rule reshaping authentication errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.gateway.sync.enabled=false",
        "portica.gateway.policy.store=memory",
        "portica.gateway.analytics.enabled=false",
        "portica.gateway.dev.api-keys[0].organization=acme",
        "portica.gateway.dev.api-keys[0].key=chain-test-key",
        "portica.gateway.dev.api-keys[0].app=chain-app",
        "portica.gateway.dev.api-keys[0].developer=dev@example.com",
        "portica.gateway.dev.api-keys[0].products[0]=pets"})
class PolicyChainIntegrationTest {

    private static final String KEY = "chain-test-key";
    private static final String ORIGIN = "https://app.example.com";

    private static HttpServer backend;
    private static final Map<String, String> seen = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    int gatewayPort;

    @Autowired
    RuntimeConfigRegistry registry;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            seen.clear();
            seen.put("method", exchange.getRequestMethod());
            seen.put("path", exchange.getRequestURI().getRawPath());
            exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), values.get(0)));
            byte[] body = "{\"pet\":{\"id\":1,\"name\":\"Rex\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Server", "backend/1.0");
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

    @BeforeEach
    void deployBundle() throws Exception {
        String target = "http://127.0.0.1:" + backend.getAddress().getPort();
        List<PolicyDefinition> policies = List.of(
                policy("cors", "CORS", Map.of("allowOrigins", List.of(ORIGIN), "allowMethods", List.of("GET", "POST"),
                        "allowHeaders", List.of("*"), "exposeHeaders", List.of("X-Served-By"))),
                policy("verify-key", "VerifyAPIKey", Map.of()),
                policy("quota", "Quota", Map.of("count", 2, "interval", 1, "timeUnit", "minute",
                        "identifier", "{request.header.x-quota-bucket}")),
                policy("enrich", "AssignMessage", Map.of(
                        "setHeaders", Map.of("X-App", "{verifyapikey.verify-key.app.name}"),
                        "removeHeaders", List.of("x-api-key"))),
                policy("call-audit", "FlowCallout", Map.of("sharedFlow", "audit")),
                policy("to-xml", "JSONToXML", Map.of()),
                policy("brand", "AssignMessage", Map.of("target", "response",
                        "setHeaders", Map.of("X-Served-By", "portica"), "removeHeaders", List.of("Server"))),
                policy("auth-error", "RaiseFault", Map.of("statusCode", 401, "contentType", "application/json",
                        "headers", Map.of("WWW-Authenticate", "ApiKey"),
                        "body", "{\"error\":\"unauthorized\",\"reason\":\"{fault.name}\"}")));
        ProxyFlows flows = new ProxyFlows(
                new FlowPair(List.of(step("cors"), step("verify-key"),
                        new FlowStep("quota", "request.header.x-quota-bucket != null"), step("enrich"),
                        step("call-audit")), List.of()),
                List.of(new ConditionalFlow("xml", "request.header.accept == \"application/xml\"", List.of(),
                        List.of(step("to-xml")))),
                new FlowPair(List.of(), List.of(step("brand"))),
                List.of(new FaultRule("auth", "fault.name == \"InvalidApiKey\" or fault.name == \"FailedToResolveAPIKey\"",
                        List.of(step("auth-error")))),
                null);
        RuntimeSharedFlow audit = new RuntimeSharedFlow("audit", 1, List.of(policy("tag", "AssignMessage",
                Map.of("setHeaders", Map.of("X-Audit", "{apiproxy.name}:{request.verb}")))), List.of(step("tag")));
        RuntimeProxy proxy = new RuntimeProxy("pets", 1, "/pets", target, "", 2_000, 10_000, policies, flows);
        registry.apply(new RuntimeEnvironmentConfig("acme", "dev", 1, Instant.now(), List.of(proxy), List.of(audit)));
        awaitRoute();
    }

    @Test
    void missingOrWrongKeysAreReshapedByTheFaultRule() throws Exception {
        HttpResponse<String> missing = send("GET", "/acme/dev/pets/1", Map.of());
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.headers().firstValue("WWW-Authenticate")).hasValue("ApiKey");
        assertThat(missing.body()).isEqualTo("{\"error\":\"unauthorized\",\"reason\":\"FailedToResolveAPIKey\"}");
        assertThat(missing.headers().firstValue("X-Portica-Request-Id")).isPresent();

        HttpResponse<String> wrong = send("GET", "/acme/dev/pets/1", Map.of("x-api-key", "nope"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("InvalidApiKey");
    }

    @Test
    void validRequestsRunTheWholeChain() throws Exception {
        HttpResponse<String> ok = send("GET", "/acme/dev/pets/1", Map.of("x-api-key", KEY));

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"pet\":{\"id\":1,\"name\":\"Rex\"}}");
        // Request side: AssignMessage and the shared flow changed what the backend received.
        assertThat(seen).containsEntry("path", "/1")
                .containsEntry("x-app", "chain-app")
                .containsEntry("x-audit", "pets:GET")
                .doesNotContainKey("x-api-key");
        // Response side: header-only AssignMessage.
        assertThat(ok.headers().firstValue("X-Served-By")).hasValue("portica");
        assertThat(ok.headers().firstValue("Server")).isEmpty();
    }

    @Test
    void conditionalFlowConvertsTheResponseToXml() throws Exception {
        HttpResponse<String> xml = send("GET", "/acme/dev/pets/1", Map.of("x-api-key", KEY, "Accept", "application/xml"));

        assertThat(xml.statusCode()).isEqualTo(200);
        assertThat(xml.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/xml"));
        assertThat(xml.body()).contains("<pet><id>1</id><name>Rex</name></pet>");
        assertThat(xml.headers().firstValue("X-Served-By")).hasValue("portica");
    }

    @Test
    void corsPreflightIsAnsweredByThePolicyAndActualRequestsGetHeaders() throws Exception {
        HttpResponse<String> preflight = send("OPTIONS", "/acme/dev/pets/1", Map.of("Origin", ORIGIN,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "x-api-key"));
        assertThat(preflight.statusCode()).isEqualTo(204);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers")).hasValue("x-api-key");

        HttpResponse<String> denied = send("OPTIONS", "/acme/dev/pets/1", Map.of("Origin", "https://evil.example.com",
                "Access-Control-Request-Method", "GET"));
        assertThat(denied.statusCode()).isEqualTo(403);

        HttpResponse<String> actual = send("GET", "/acme/dev/pets/1", Map.of("x-api-key", KEY, "Origin", ORIGIN));
        assertThat(actual.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        assertThat(actual.headers().firstValue("Access-Control-Expose-Headers")).hasValue("X-Served-By");

        // Errors carry CORS headers too, so browsers can read them.
        HttpResponse<String> error = send("GET", "/acme/dev/pets/1", Map.of("Origin", ORIGIN));
        assertThat(error.statusCode()).isEqualTo(401);
        assertThat(error.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
    }

    @Test
    void quotaAppliesWhenItsStepConditionMatches() throws Exception {
        Map<String, String> headers = Map.of("x-api-key", KEY, "x-quota-bucket", UUID.randomUUID().toString());
        HttpResponse<String> first = send("GET", "/acme/dev/pets/1", headers);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("RateLimit-Remaining")).hasValue("1");
        assertThat(send("GET", "/acme/dev/pets/1", headers).statusCode()).isEqualTo(200);

        HttpResponse<String> limited = send("GET", "/acme/dev/pets/1", headers);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body()).contains("QuotaViolation");

        // Without the header the step is skipped entirely.
        assertThat(send("GET", "/acme/dev/pets/1", Map.of("x-api-key", KEY)).headers()
                .firstValue("RateLimit-Remaining")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, new LinkedHashMap<>(config));
    }

    private static FlowStep step(String policy) {
        return new FlowStep(policy, null);
    }

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Route refresh is asynchronous: wait until the proxy answers (401 without a key). */
    private void awaitRoute() throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        int status = send("GET", "/acme/dev/pets/1", Map.of()).statusCode();
        while (status == 404 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            status = send("GET", "/acme/dev/pets/1", Map.of()).statusCode();
        }
        assertThat(status).as("route deployed").isEqualTo(401);
    }
}
