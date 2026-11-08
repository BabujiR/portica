package com.portica.gateway;

import com.portica.common.policy.FlowPair;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;
import com.portica.common.security.Hashing;
import com.portica.common.security.Pem;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeApiProduct;
import com.portica.gateway.runtime.RuntimeModel.RuntimeCredential;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeTargetTls;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.portica.gateway.policy.credentials.TestCredentials.environment;
import static com.portica.gateway.policy.credentials.TestCredentials.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 TLS: the gateway terminates TLS (Spring Boot SSL bundle) and asks for client certificates;
 * VerifyClientCertificate identifies the app by the certificate registered on its credential (alone, or
 * bound to the app of an API key); target servers with TLS settings are called with the gateway's client
 * certificate (mutual TLS to the backend) and a private CA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"portica.gateway.sync.enabled=false", "portica.gateway.policy.store=memory",
                "portica.gateway.analytics.enabled=false",
                "server.ssl.bundle=gateway", "server.ssl.client-auth=want",
                "spring.ssl.bundle.pem.gateway.keystore.certificate=classpath:tls/server.crt",
                "spring.ssl.bundle.pem.gateway.keystore.private-key=classpath:tls/server.key",
                "spring.ssl.bundle.pem.gateway.truststore.certificate=classpath:tls/ca.crt"})
class MutualTlsIntegrationTest {

    private static final AtomicLong VERSION = new AtomicLong();
    private static HttpServer plainBackend;
    private static HttpsServer mtlsBackend;

    @Value("${local.server.port}")
    int port;

    @Autowired
    RuntimeConfigRegistry registry;

    private static HttpClient withCertificate;
    private static HttpClient withOtherCertificate;
    private static HttpClient withoutCertificate;

    @BeforeAll
    static void start() throws Exception {
        plainBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plainBackend.createContext("/", exchange -> respond(exchange, "plain"));
        plainBackend.start();

        // A backend that only talks to clients holding a certificate from the test CA
        mtlsBackend = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mtlsBackend.setHttpsConfigurator(new HttpsConfigurator(sslContext("tls/server.p12")) {
            @Override
            public void configure(HttpsParameters params) {
                var parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        mtlsBackend.createContext("/", exchange -> {
            String peer;
            try {
                peer = ((X509Certificate) ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0])
                        .getSubjectX500Principal().getName();
            } catch (SSLPeerUnverifiedException e) {
                peer = "none";
            }
            respond(exchange, peer);
        });
        mtlsBackend.start();

        withCertificate = client(sslContext("tls/client.p12"));
        withOtherCertificate = client(sslContext("tls/other.p12"));
        withoutCertificate = client(sslContext(null));
    }

    @AfterAll
    static void stop() {
        plainBackend.stop(0);
        mtlsBackend.stop(0);
    }

    @BeforeEach
    void deploy() throws Exception {
        String plain = "http://127.0.0.1:" + plainBackend.getAddress().getPort();
        String secure = "https://127.0.0.1:" + mtlsBackend.getAddress().getPort();
        RuntimeTargetTls tls = new RuntimeTargetTls(read("tls/gateway-client.crt"), read("tls/gateway-client.key"),
                read("tls/ca.crt"), true);
        RuntimeTargetTls trustOnly = new RuntimeTargetTls(null, null, read("tls/ca.crt"), true);

        List<RuntimeProxy> proxies = List.of(
                proxy("certified", plain, null, policy("cert", "VerifyClientCertificate", Map.of())),
                proxy("bound", plain, null, policy("key", "VerifyAPIKey", Map.of()),
                        policy("cert", "VerifyClientCertificate", Map.of("bindToApp", true))),
                proxy("backend", secure, tls),
                proxy("backend-no-cert", secure, trustOnly));
        List<RuntimeApiProduct> products = List.of(product("certs", List.of("certified", "bound"), List.of(), List.of(), null));
        X509Certificate shopCertificate = Pem.certificates(read("tls/client.crt")).get(0);
        List<RuntimeCredential> credentials = List.of(
                credential("shop-key", "shop", List.of(Pem.fingerprint(shopCertificate))),
                credential("other-key", "other", List.of()));
        registry.apply(environment(VERSION.incrementAndGet(), proxies, products, credentials, List.of()));
        awaitRoute("/acme/dev/certified/x", 401);
    }

    @AfterEach
    void undeploy() {
        registry.remove(List.of("acme/dev"));
    }

    @Test
    void appsAreIdentifiedByTheirRegisteredClientCertificate() throws Exception {
        HttpResponse<String> ok = get(withCertificate, "/acme/dev/certified/orders", Map.of());
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("plain");

        HttpResponse<String> none = get(withoutCertificate, "/acme/dev/certified/orders", Map.of());
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).contains("ClientCertificateRequired");

        HttpResponse<String> unknown = get(withOtherCertificate, "/acme/dev/certified/orders", Map.of());
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(unknown.body()).contains("UnknownClientCertificate");
    }

    @Test
    void boundCertificatesMustBelongToTheApiKeysApp() throws Exception {
        assertThat(get(withCertificate, "/acme/dev/bound/x", Map.of("x-api-key", "shop-key")).statusCode())
                .isEqualTo(200);

        HttpResponse<String> foreign = get(withCertificate, "/acme/dev/bound/x", Map.of("x-api-key", "other-key"));
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).contains("CertificateNotBoundToApp");
    }

    @Test
    void targetServersWithTlsSettingsAreCalledWithTheGatewaysClientCertificate() throws Exception {
        HttpResponse<String> response = get(withoutCertificate, "/acme/dev/backend/ping", Map.of());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("CN=portica-gateway");

        assertThat(get(withoutCertificate, "/acme/dev/backend-no-cert/ping", Map.of()).statusCode())
                .as("the backend refuses the handshake without a client certificate").isGreaterThanOrEqualTo(500);
    }

    // ----------------------------------------------------------------- helpers

    private static RuntimeProxy proxy(String name, String target, RuntimeTargetTls tls, PolicyDefinition... policies) {
        List<FlowStep> steps = java.util.Arrays.stream(policies).map(p -> new FlowStep(p.name(), null)).toList();
        ProxyFlows flows = steps.isEmpty() ? ProxyFlows.EMPTY
                : new ProxyFlows(new FlowPair(steps, null), null, null, null, null);
        return new RuntimeProxy(name, 1, "/" + name, target, "", 2_000, 10_000, List.of(policies), flows, tls);
    }

    private static PolicyDefinition policy(String name, String type, Map<String, Object> config) {
        return new PolicyDefinition(name, type, null, null, new LinkedHashMap<>(config));
    }

    private static RuntimeCredential credential(String key, String app, List<String> fingerprints) {
        return new RuntimeCredential("cred-" + app, Hashing.sha256Hex(key), Hashing.sha256Hex(key + "-secret"), null,
                "app-" + app, app, Map.of(), "dev-1", "ada@example.com", List.of("certs"), fingerprints);
    }

    private HttpResponse<String> get(HttpClient client, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitRoute(String path, int status) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        int s = get(withoutCertificate, path, Map.of()).statusCode();
        while (s != status && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
            s = get(withoutCertificate, path, Map.of()).statusCode();
        }
        assertThat(s).as("route %s ready", path).isEqualTo(status);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static HttpClient client(SSLContext context) {
        return HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Trusts the test CA; presents the key pair in {@code keyStore} (PKCS #12, password changeit) if given. */
    private static SSLContext sslContext(String keyStore) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", Pem.certificates(read("tls/ca.crt")).get(0));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        KeyManagerFactory kmf = null;
        if (keyStore != null) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = new ClassPathResource(keyStore).getInputStream()) {
                keys.load(in, "changeit".toCharArray());
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, "changeit".toCharArray());
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
