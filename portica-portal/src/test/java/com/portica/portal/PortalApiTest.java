package com.portica.portal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portal BFF against a stub management API and a stub gateway: public catalog, developer-scoped
 * calls carrying the verified e-mail address, path allow-listing, and try-it through the gateway.
 */
@SpringBootTest(properties = {"portica.portal.auth.token-uri=", "portica.portal.try-it.requests-per-minute=5"})
@AutoConfigureMockMvc
class PortalApiTest {

    private static HttpServer management;
    private static HttpServer gateway;
    private static final Map<String, String> seen = new ConcurrentHashMap<>();

    @Autowired
    MockMvc mvc;

    static {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Started before the Spring context, whose URLs point here. */
    static void start() throws IOException {
        management = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        management.createContext("/internal/v1/portal/organizations/acme/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/internal/v1/portal/organizations/acme/".length());
            seen.put("management.path", exchange.getRequestURI().toString());
            seen.put("management.method", exchange.getRequestMethod());
            seen.put("management.developer", String.valueOf(exchange.getRequestHeaders().getFirst("X-Portica-Developer")));
            switch (path) {
                case "catalog" -> respond(exchange, 200, """
                        {"organization":"acme","products":[{"name":"pets"}],
                         "apis":[{"name":"pets","deployments":[{"environment":"dev","revision":1,"basePath":"/pets"}]}],
                         "mcpServers":[]}""");
                case "me/apps" -> respond(exchange, 200, "[{\"name\":\"mobile\"}]");
                case "me" -> respond(exchange, 404, "{\"title\":\"Resource not found\",\"status\":404}");
                default -> respond(exchange, 204, "");
            }
        });
        management.start();
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            seen.put("gateway.uri", exchange.getRequestURI().toString());
            seen.put("gateway.cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            seen.put("gateway.apikey", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            exchange.getResponseHeaders().add("Set-Cookie", "secret=1");
            respond(exchange, 201, "{\"id\":7}");
        });
        gateway.start();
    }

    @AfterAll
    static void stop() {
        management.stop(0);
        gateway.stop(0);
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("portica.portal.management-url", () -> "http://127.0.0.1:" + management.getAddress().getPort());
        registry.add("portica.portal.gateway-url", () -> "http://127.0.0.1:" + gateway.getAddress().getPort());
    }

    @Test
    void theCatalogIsPublic() throws Exception {
        mvc.perform(get("/api/portal/organizations/acme/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apis[0].name").value("pets"))
                .andExpect(header().string("Cache-Control", containsString("max-age")));
        assertThat(seen.get("management.developer")).isEqualTo("null");
    }

    @Test
    void personalCallsCarryTheVerifiedEmail() throws Exception {
        mvc.perform(get("/api/portal/organizations/acme/me/apps")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/portal/organizations/acme/me/apps").with(developer("Ada@Example.com", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mobile"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        assertThat(seen.get("management.developer")).isEqualTo("ada@example.com");

        mvc.perform(get("/api/portal/organizations/acme/me").with(developer("ada@example.com", true)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/portal/organizations/acme/me/apps").with(developer("eve@example.com", false)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/portal/organizations/acme/me/apps/mobile").with(developer("ada@example.com", true)))
                .andExpect(status().isNoContent());
        assertThat(seen.get("management.method")).isEqualTo("DELETE");
    }

    @Test
    void onlyPortalResourcesAreForwarded() throws Exception {
        mvc.perform(get("/api/portal/organizations/acme/developers").with(developer("ada@example.com", true)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/portal/organizations/acme/me/apps/x/keys/not-a-uuid").with(developer("ada@example.com", true)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/portal/organizations/acme/me/usage?from=2026-01-01T00:00:00Z&evil=1")
                        .with(developer("ada@example.com", true)))
                .andExpect(status().isNoContent());
        assertThat(seen.get("management.path")).endsWith("/me/usage?from=2026-01-01T00%3A00%3A00Z");
    }

    @Test
    void tryItGoesThroughTheGatewayAtThePublishedBasePath() throws Exception {
        String request = """
                {"environment":"dev","api":"pets","method":"post","path":"/pets?limit=2",
                 "headers":{"x-api-key":"k-123","Cookie":"session=1","Host":"evil.example"},"body":"{\\"name\\":\\"rex\\"}"}
                """;
        mvc.perform(post("/api/portal/organizations/acme/try").with(developer("try@example.com", true))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.body").value("{\"id\":7}"))
                .andExpect(jsonPath("$.headers['set-cookie']").doesNotExist());
        assertThat(seen.get("gateway.uri")).isEqualTo("/acme/dev/pets/pets?limit=2");
        assertThat(seen.get("gateway.apikey")).isEqualTo("k-123");
        assertThat(seen.get("gateway.cookie")).isEqualTo("null");

        mvc.perform(post("/api/portal/organizations/acme/try").with(developer("try@example.com", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"prod\",\"api\":\"pets\",\"method\":\"GET\",\"path\":\"/\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/portal/organizations/acme/try").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"dev\",\"api\":\"pets\",\"method\":\"GET\",\"path\":\"/\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tryItIsRateLimitedPerDeveloper() throws Exception {
        String request = "{\"environment\":\"dev\",\"api\":\"pets\",\"method\":\"GET\",\"path\":\"/\"}";
        int limited = 0;
        for (int i = 0; i < 7; i++) {
            int status = mvc.perform(post("/api/portal/organizations/acme/try").with(developer("busy@example.com", true))
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(2);
    }

    private static RequestPostProcessor developer(String email, boolean verified) {
        return jwt().jwt(token -> token.subject("user-1").claim("email", email).claim("email_verified", verified));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
