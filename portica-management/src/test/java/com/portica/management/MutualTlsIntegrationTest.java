package com.portica.management;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8: client certificates registered on app credentials reach the gateway feed as fingerprints;
 * target servers carry mutual TLS settings, with the client key encrypted at rest and decrypted only
 * in the runtime feed.
 */
class MutualTlsIntegrationTest extends AbstractIntegrationTest {

    /** SHA-256 of tls/client.crt, lowercase hex. */
    private static final String CLIENT_FINGERPRINT = fingerprint(read("tls/client.crt"));

    @Autowired
    JdbcClient jdbc;

    @Test
    void clientCertificatesAreRegisteredOnCredentials() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"dev\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/apiproducts", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"shop\",\"proxies\":[\"orders\"]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/developers", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ada@example.com\",\"firstName\":\"Ada\",\"lastName\":\"L\"}"))
                .andExpect(status().isCreated());
        String created = mvc.perform(post("/api/v1/organizations/{org}/developers/ada@example.com/apps", org)
                        .with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shop-app\",\"apiProducts\":[\"shop\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String keyId = JsonPath.read(created, "$.credential.id");
        String certificates = "/api/v1/organizations/{org}/developers/ada@example.com/apps/shop-app/keys/{key}/certificates";

        mvc.perform(post(certificates, org, keyId).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("certificate", read("tls/client.crt")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificates", hasSize(1)))
                .andExpect(jsonPath("$.certificates[0].fingerprint").value(CLIENT_FINGERPRINT))
                .andExpect(jsonPath("$.certificates[0].subject").value("CN=app-shop"));
        mvc.perform(post(certificates, org, keyId).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("certificate", read("tls/client.crt")))))
                .andExpect(status().isConflict());
        mvc.perform(post(certificates, org, keyId).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("certificate", "not a certificate"))))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.credentials[0].certificateFingerprints", hasItem(CLIENT_FINGERPRINT)));

        mvc.perform(delete(certificates + "/{fp}", org, keyId, CLIENT_FINGERPRINT).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates", hasSize(0)));
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.credentials[0].certificateFingerprints", hasSize(0)));
    }

    @Test
    void targetServersCarryMutualTlsSettingsWithAnEncryptedKey() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"dev\"}")).andExpect(status().isCreated());
        String servers = "/api/v1/organizations/{org}/environments/dev/target-servers";
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("clientCertificate", read("tls/gateway-client.crt"));
        tls.put("clientKey", read("tls/gateway-client.key"));
        tls.put("trustedCertificates", read("tls/ca.crt"));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "orders-backend", "host", "orders.internal",
                "port", 8443, "protocol", "HTTPS"));
        body.put("tls", tls);

        mvc.perform(post(servers, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tls.clientCertificateSubject").value("CN=portica-gateway"))
                .andExpect(jsonPath("$.tls.clientKeyConfigured").value(true))
                .andExpect(jsonPath("$.tls.trustedCertificateCount").value(1))
                .andExpect(jsonPath("$.tls.clientKey").doesNotExist());
        String stored = jdbc.sql("SELECT tls_client_key_encrypted FROM target_servers WHERE name = 'orders-backend'"
                        + " AND host = 'orders.internal' ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single();
        assertThat(stored).startsWith("s2:").doesNotContain("PRIVATE KEY");

        // update without the key keeps it (certificate unchanged); a key that doesn't match is refused
        Map<String, Object> keep = new LinkedHashMap<>(tls);
        keep.remove("clientKey");
        Map<String, Object> update = new LinkedHashMap<>(Map.of("host", "orders.internal", "port", 8443, "protocol", "HTTPS"));
        update.put("tls", keep);
        mvc.perform(put(servers + "/orders-backend", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tls.clientKeyConfigured").value(true));
        Map<String, Object> mismatch = new LinkedHashMap<>(tls);
        mismatch.put("clientKey", read("tls/client.key"));
        update.put("tls", mismatch);
        mvc.perform(put(servers + "/orders-backend", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isBadRequest());
        update.put("protocol", "HTTP");
        update.put("tls", keep);
        mvc.perform(put(servers + "/orders-backend", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isBadRequest());

        // the gateway feed gets the decrypted key for proxies using the server
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"orders\",\"revision\":{\"basePath\":\"/orders\",\"target\":{\"server\":\"orders-backend\"}}}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/orders/revisions/1/deployments", org)
                .with(person(alice))).andExpect(status().is2xxSuccessful());
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.proxies[0].targetTls.clientKey").value(startsWith("-----BEGIN PRIVATE KEY-----")))
                .andExpect(jsonPath("$.proxies[0].targetTls.verifyHostname").value(true));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private static String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fingerprint(String pem) {
        return com.portica.common.security.Pem.fingerprint(com.portica.common.security.Pem.certificates(pem).get(0));
    }
}
