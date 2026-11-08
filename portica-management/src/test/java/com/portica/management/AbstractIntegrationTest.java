package com.portica.management;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for management API integration tests: one PostgreSQL container (started once per
 * JVM and reused by every test class, so they also share one cached Spring context), simulated
 * Keycloak tokens, and helpers. Tests use unique names, so no cleanup is needed between them.
 * Skipped automatically when Docker is not available.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak.test/realms/portica",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://keycloak.test/realms/portica/protocol/openid-connect/certs",
        // test-only AES-256 key for encrypted key value maps
        "portica.kvm.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        // no analytics store: analytics reads answer 503 (AnalyticsQueryIntegrationTest covers ClickHouse)
        "portica.clickhouse.url=http://127.0.0.1:1",
        "portica.clickhouse.timeout=2s"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    @SuppressWarnings("rawtypes")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        // Started once and stopped by Testcontainers' JVM shutdown hook (Ryuk).
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    protected static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    protected MockMvc mvc;

    /** Creates an organization (no default environments) with {@code admin} as its ORG_ADMIN. */
    protected String createOrganization(String admin) throws Exception {
        String org = uniqueName("org");
        mvc.perform(post("/api/v1/organizations").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","adminEmail":"%s","createDefaultEnvironments":false}
                                """.formatted(org, email(admin))))
                .andExpect(status().isCreated());
        return org;
    }

    protected String userId(String person) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me").with(person(person)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    protected static RequestPostProcessor person(String name) {
        return jwt().jwt(token -> token
                .subject("sub-" + name)
                .claim("email", email(name))
                .claim("email_verified", true)
                .claim("name", name));
    }

    protected static RequestPostProcessor platformAdmin() {
        return jwt()
                .jwt(token -> token
                        .subject("sub-platform-admin")
                        .claim("email", "platform-admin@portica.test")
                        .claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority("REALM_portica-platform-admin"));
    }

    /** A gateway service account (client credentials token with the runtime role). */
    protected static RequestPostProcessor gateway() {
        return jwt()
                .jwt(token -> token.subject("service-account-portica-gateway"))
                .authorities(new SimpleGrantedAuthority("REALM_portica-runtime"));
    }

    protected static String email(String name) {
        return name + "@portica.test";
    }

    protected static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static Matcher<String> containsPath(String path) {
        return Matchers.endsWith(path);
    }
}
