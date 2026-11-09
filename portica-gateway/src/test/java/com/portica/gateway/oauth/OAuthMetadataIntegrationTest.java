package com.portica.gateway.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** OAuth discovery documents MCP clients read to find the token endpoint (RFC 9728, RFC 8414). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.gateway.sync.enabled=false",
        "portica.gateway.policy.store=memory",
        "portica.gateway.analytics.enabled=false"})
class OAuthMetadataIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void protectedResourceMetadataNamesTheEnvironmentsAuthorizationServer() throws Exception {
        JsonNode prm = get("/.well-known/oauth-protected-resource/acme/dev/mcp/petstore", null);
        String issuer = "http://localhost:" + port + "/acme/dev";
        assertThat(prm.path("resource").asString()).isEqualTo(issuer + "/mcp/petstore");
        assertThat(prm.path("authorization_servers").get(0).asString()).isEqualTo(issuer);

        JsonNode as = get("/.well-known/oauth-authorization-server/acme/dev", null);
        assertThat(as.path("issuer").asString()).isEqualTo(issuer);
        assertThat(as.path("token_endpoint").asString()).isEqualTo(issuer + "/oauth2/token");
        assertThat(as.path("grant_types_supported").get(0).asString()).isEqualTo("client_credentials");
    }

    @Test
    void honoursTheLoadBalancersHostAndRejectsOddValues() throws Exception {
        JsonNode as = get("/.well-known/oauth-authorization-server/acme/dev", "api.example.com");
        assertThat(as.path("issuer").asString()).isEqualTo("https://api.example.com/acme/dev");
        JsonNode odd = get("/.well-known/oauth-authorization-server/acme/dev", "evil.example.com/<script>");
        assertThat(odd.path("issuer").asString()).isEqualTo("https://localhost:" + port + "/acme/dev");
    }

    private JsonNode get(String path, String forwardedHost) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (forwardedHost != null) {
            request.header("X-Forwarded-Host", forwardedHost).header("X-Forwarded-Proto", "https");
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
