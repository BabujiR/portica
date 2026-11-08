package com.portica.gateway;

import com.jayway.jsonpath.JsonPath;
import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeApiProduct;
import com.portica.gateway.runtime.RuntimeModel.RuntimeCredential;
import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeQuota;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.portica.gateway.policy.credentials.TestCredentials.credential;
import static com.portica.gateway.policy.credentials.TestCredentials.environment;
import static com.portica.gateway.policy.credentials.TestCredentials.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 at the gateway: developer app credentials from the environment snapshot, the client
 * credentials token endpoint, OAuthV2 with Portica tokens, VerifyAPIKey with product authorization,
 * product quotas and key value maps, plus revocation through a new snapshot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"portica.gateway.sync.enabled=false", "portica.gateway.policy.store=memory",
        "portica.gateway.analytics.enabled=false"})
class DeveloperAppsIntegrationTest {

    private static final AtomicLong VERSION = new AtomicLong();
    private static HttpServer backend;
    private static final Map<String, String> seen = new ConcurrentHashMap<>();

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuntimeConfigRegistry registry;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String target;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            seen.clear();
            exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), values.get(0)));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    // ----------------------------------------------------------------- snapshot

    private static final List<RuntimeApiProduct> PRODUCTS = List.of(
            product("pets-gold", List.of("pets", "keyed"), List.of(), List.of("pets.read", "pets.write"),
                    new RuntimeQuota(1000, 1, "minute")),
            product("pets-limited", List.of("pets"), List.of(), List.of("pets.read"), new RuntimeQuota(2, 1, "minute")),
            product("orders", List.of("orders"), List.of(), List.of("orders.write"), null));

    private static final List<RuntimeCredential> CREDENTIALS = List.of(
            credential("gold-key", "gold-secret", "mobile", null, "pets-gold"),
            credential("limited-key", "limited-secret", "trial", null, "pets-limited"),
            credential("orders-key", "orders-secret", "shop", null, "orders"));

    @BeforeEach
    void deploy() throws Exception {
        target = "http://127.0.0.1:" + backend.getAddress().getPort();
        apply(CREDENTIALS);
    }

    /** The Spring context (and registry) is shared with other test classes: leave no environment behind. */
    @AfterEach
    void undeploy() {
        registry.remove(List.of("acme/dev"));
    }

    private void apply(List<RuntimeCredential> credentials) throws Exception {
        List<PolicyDefinition> petsPolicies = List.of(
                policy("oauth", "OAuthV2", Map.of("requiredScopes", List.of("pets.read"))),
                policy("quota", "Quota", Map.of("useProductQuota", true)),
                policy("secrets", "KeyValueMapOperations", Map.of("mapName", "backend-secrets",
                        "get", List.of(Map.of("key", "token", "assignTo", "private.backend.token")))),
                policy("enrich", "AssignMessage", Map.of("setHeaders", Map.of(
                        "X-Backend-Token", "{private.backend.token}", "X-App", "{oauthv2.oauth.app.name}"),
                        "removeHeaders", List.of("Authorization"))));
        RuntimeProxy pets = new RuntimeProxy("pets", 1, "/pets", target, "", 2_000, 10_000, petsPolicies,
                new ProxyFlows(new FlowPair(steps("oauth", "quota", "secrets", "enrich"), null), null, null, null, null));
        RuntimeProxy keyed = new RuntimeProxy("keyed", 1, "/keyed", target, "", 2_000, 10_000,
                List.of(policy("verify", "VerifyAPIKey", Map.of())),
                new ProxyFlows(new FlowPair(steps("verify"), null), null, null, null, null));
        RuntimeKeyValueMap secrets = new RuntimeKeyValueMap("backend-secrets", "environment", true,
                Map.of("token", "backend-s3cret"));
        registry.apply(environment(VERSION.incrementAndGet(), List.of(pets, keyed), PRODUCTS, credentials, List.of(secrets)));
        awaitRoute("/acme/dev/keyed/1", 401);
    }

    // ----------------------------------------------------------------- token endpoint

    @Test
    void guessingAClientSecretIsThrottledPerClientId() throws Exception {
        int throttled = 0;
        String retryAfter = null;
        for (int i = 0; i < 35; i++) {
            HttpResponse<String> r = token(basic("brute-force-target", "guess-" + i), "grant_type=client_credentials");
            if (r.statusCode() == 429) {
                throttled++;
                retryAfter = r.headers().firstValue("Retry-After").orElse(null);
            } else {
                assertThat(r.statusCode()).isEqualTo(401);
            }
        }
        assertThat(throttled).as("30 attempts per minute, then 429").isGreaterThanOrEqualTo(4);
        assertThat(retryAfter).isNotNull();
        // other clients are unaffected
        assertThat(token(basic("gold-key", "gold-secret"), "grant_type=client_credentials").statusCode()).isEqualTo(200);
    }

    @Test
    void clientCredentialsGrantIssuesABearerToken() throws Exception {
        HttpResponse<String> response = token(basic("gold-key", "gold-secret"), "grant_type=client_credentials");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(JsonPath.<String>read(response.body(), "$.token_type")).isEqualTo("Bearer");
        assertThat(JsonPath.<Integer>read(response.body(), "$.expires_in")).isEqualTo(3600);
        assertThat(JsonPath.<String>read(response.body(), "$.scope")).isEqualTo("pets.read pets.write");
        assertThat(JsonPath.<String>read(response.body(), "$.access_token")).hasSizeGreaterThan(40);

        // client_id/client_secret in the body work too, and scopes can be narrowed.
        HttpResponse<String> narrowed = token(null,
                "grant_type=client_credentials&client_id=gold-key&client_secret=gold-secret&scope=pets.read");
        assertThat(narrowed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(narrowed.body(), "$.scope")).isEqualTo("pets.read");
    }

    @Test
    void tokenErrorsFollowRfc6749() throws Exception {
        HttpResponse<String> badSecret = token(basic("gold-key", "wrong"), "grant_type=client_credentials");
        assertThat(badSecret.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(badSecret.body(), "$.error")).isEqualTo("invalid_client");
        assertThat(badSecret.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Basic"));

        assertThat(error(token(basic("nobody", "x"), "grant_type=client_credentials"))).isEqualTo("invalid_client");
        assertThat(error(token(basic("gold-key", "gold-secret"), "grant_type=password"))).isEqualTo("unsupported_grant_type");
        assertThat(error(token(basic("gold-key", "gold-secret"), "scope=pets.read"))).isEqualTo("invalid_request");
        assertThat(error(token(basic("gold-key", "gold-secret"), "grant_type=client_credentials&scope=admin")))
                .isEqualTo("invalid_scope");
        assertThat(error(token(basic("gold-key", "gold-secret"),
                "grant_type=client_credentials&grant_type=client_credentials"))).isEqualTo("invalid_request");

        HttpResponse<String> json = send(HttpRequest.newBuilder(uri("/acme/dev/oauth2/token"))
                .header("Content-Type", "application/json").header("Authorization", basic("gold-key", "gold-secret"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(json.statusCode()).isEqualTo(400);

        HttpResponse<String> unknownEnv = send(HttpRequest.newBuilder(uri("/acme/nowhere/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")));
        assertThat(unknownEnv.statusCode()).isEqualTo(404);
    }

    // ----------------------------------------------------------------- OAuthV2 with Portica tokens

    @Test
    void tokensOpenTheirProductsProxiesWithKvmSecretsForwarded() throws Exception {
        String token = accessToken("gold-key", "gold-secret", null);

        HttpResponse<String> ok = get("/acme/dev/pets/1", Map.of("Authorization", "Bearer " + token));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(seen).containsEntry("x-app", "mobile")
                .containsEntry("x-backend-token", "backend-s3cret")
                .doesNotContainKey("authorization");

        assertThat(get("/acme/dev/pets/1", Map.of()).statusCode()).isEqualTo(401);
        HttpResponse<String> garbage = get("/acme/dev/pets/1", Map.of("Authorization", "Bearer not-a-token"));
        assertThat(garbage.statusCode()).isEqualTo(401);
        assertThat(garbage.body()).contains("InvalidAccessToken");
    }

    @Test
    void scopesAndProductsAreEnforced() throws Exception {
        String writeOnly = accessToken("gold-key", "gold-secret", "pets.write");
        HttpResponse<String> noScope = get("/acme/dev/pets/1", Map.of("Authorization", "Bearer " + writeOnly));
        assertThat(noScope.statusCode()).isEqualTo(403);
        assertThat(noScope.body()).contains("InsufficientScope");

        String ordersToken = accessToken("orders-key", "orders-secret", null);
        HttpResponse<String> wrongProduct = get("/acme/dev/pets/1", Map.of("Authorization", "Bearer " + ordersToken));
        assertThat(wrongProduct.statusCode()).isEqualTo(403);
        assertThat(wrongProduct.body()).contains("NoApiProductMatchFound");
    }

    @Test
    void productQuotaLimitsEachApp() throws Exception {
        String token = accessToken("limited-key", "limited-secret", null);
        Map<String, String> auth = Map.of("Authorization", "Bearer " + token);

        assertThat(get("/acme/dev/pets/1", auth).headers().firstValue("RateLimit-Limit")).hasValue("2");
        assertThat(get("/acme/dev/pets/1", auth).statusCode()).isEqualTo(200);
        HttpResponse<String> limited = get("/acme/dev/pets/1", auth);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("QuotaViolation");
    }

    @Test
    void revokingTheCredentialOrTheTokenStopsIt() throws Exception {
        String token = accessToken("gold-key", "gold-secret", null);
        Map<String, String> auth = Map.of("Authorization", "Bearer " + token);
        assertThat(get("/acme/dev/pets/1", auth).statusCode()).isEqualTo(200);

        // RFC 7009 revocation by the client itself.
        HttpResponse<String> revoked = send(HttpRequest.newBuilder(uri("/acme/dev/oauth2/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", basic("gold-key", "gold-secret"))
                .POST(HttpRequest.BodyPublishers.ofString("token=" + token)));
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(get("/acme/dev/pets/1", auth).statusCode()).isEqualTo(401);

        // The management plane revokes the key: a new snapshot without it stops existing tokens.
        String second = accessToken("gold-key", "gold-secret", null);
        assertThat(get("/acme/dev/pets/1", Map.of("Authorization", "Bearer " + second)).statusCode()).isEqualTo(200);
        List<RuntimeCredential> withoutGold = new ArrayList<>(CREDENTIALS);
        withoutGold.removeIf(c -> c.appName().equals("mobile"));
        apply(withoutGold);
        assertThat(get("/acme/dev/pets/1", Map.of("Authorization", "Bearer " + second)).statusCode()).isEqualTo(401);
        assertThat(error(token(basic("gold-key", "gold-secret"), "grant_type=client_credentials"))).isEqualTo("invalid_client");
    }

    // ----------------------------------------------------------------- VerifyAPIKey with products

    @Test
    void apiKeysNeedAProductIncludingTheProxy() throws Exception {
        assertThat(get("/acme/dev/keyed/1", Map.of("x-api-key", "gold-key")).statusCode()).isEqualTo(200);
        HttpResponse<String> orders = get("/acme/dev/keyed/1", Map.of("x-api-key", "orders-key"));
        assertThat(orders.statusCode()).isEqualTo(403);
        assertThat(orders.body()).contains("NoApiProductMatchFound");
        assertThat(get("/acme/dev/keyed/1", Map.of("x-api-key", "unknown")).statusCode()).isEqualTo(401);
    }

    // ----------------------------------------------------------------- helpers

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, new LinkedHashMap<>(config));
    }

    private static List<FlowStep> steps(String... names) {
        return java.util.Arrays.stream(names).map(n -> new FlowStep(n, null)).toList();
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private String accessToken(String id, String secret, String scope) throws Exception {
        HttpResponse<String> r = token(basic(id, secret), "grant_type=client_credentials" + (scope == null ? "" : "&scope=" + scope));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JsonPath.read(r.body(), "$.access_token");
    }

    private HttpResponse<String> token(String authorization, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/acme/dev/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return send(b);
    }

    private static String error(HttpResponse<String> r) {
        return JsonPath.read(r.body(), "$.error");
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        headers.forEach(b::header);
        return send(b);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void awaitRoute(String path, int status) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        int s = get(path, Map.of()).statusCode();
        while (s != status && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            s = get(path, Map.of()).statusCode();
        }
        assertThat(s).as("route %s ready", path).isEqualTo(status);
    }
}
