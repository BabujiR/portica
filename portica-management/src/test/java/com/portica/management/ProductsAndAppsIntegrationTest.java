package com.portica.management;

import com.jayway.jsonpath.JsonPath;
import com.portica.common.security.Hashing;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 4: API products, developers, apps, credential issuance/rotation/revocation and the runtime feed. */
class ProductsAndAppsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void productCrudAndValidation() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));

        createProduct(org, """
                {"name":"pets-gold","displayName":"Pets Gold","approvalType":"AUTO","environments":["dev"],
                 "proxies":["pets"],"paths":["/pets/**"],"scopes":["pets.read","pets.write"],
                 "quota":{"count":1000,"interval":1,"timeUnit":"hour"},"attributes":{"tier":"gold"}}
                """, person(alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalType").value("AUTO"))
                .andExpect(jsonPath("$.access").value("PUBLIC"))
                .andExpect(jsonPath("$.quota.count").value(1000))
                .andExpect(jsonPath("$.scopes", hasSize(2)));

        createProduct(org, "{\"name\":\"pets-gold\",\"proxies\":[\"pets\"]}", person(alice)).andExpect(status().isConflict());
        createProduct(org, "{\"name\":\"bad\",\"environments\":[\"nowhere\"]}", person(alice))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("nowhere")));
        createProduct(org, "{\"name\":\"bad\",\"scopes\":[\"has space\"],\"paths\":[\"no-slash\"]}", person(alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/organizations/{org}/apiproducts/pets-gold", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proxies\":[\"pets\",\"toys\"],\"approvalType\":\"MANUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proxies", hasSize(2)))
                .andExpect(jsonPath("$.approvalType").value("MANUAL"))
                .andExpect(jsonPath("$.quota").value(nullValue()));

        mvc.perform(get("/api/v1/organizations/{org}/apiproducts", org).with(person(alice)))
                .andExpect(jsonPath("$.items[0].name").value("pets-gold"));
        mvc.perform(delete("/api/v1/organizations/{org}/apiproducts/pets-gold", org).with(person(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/{org}/apiproducts/pets-gold", org).with(person(uniqueName("mallory"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void appCredentialsLifecycleReachesTheGatewayFeed() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createEnvironment(org, "prod", person(alice));
        createProduct(org, """
                {"name":"pets-gold","environments":["dev"],"proxies":["pets"],"scopes":["pets.read"],
                 "quota":{"count":10,"timeUnit":"minute"}}
                """, person(alice)).andExpect(status().isCreated());
        createProduct(org, "{\"name\":\"premium\",\"approvalType\":\"MANUAL\",\"proxies\":[\"pets\"]}", person(alice))
                .andExpect(status().isCreated());

        // Developer (email is case-insensitive) and app with its first credential.
        mvc.perform(post("/api/v1/organizations/{org}/developers", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Ada@Example.com\",\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/organizations/{org}/developers", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"firstName\":\"A\",\"lastName\":\"L\"}"))
                .andExpect(status().isConflict());

        long versionBefore = configVersion(org, "dev");
        MvcResult created = mvc.perform(post("/api/v1/organizations/{org}/developers/ada@example.com/apps", org)
                        .with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mobile\",\"callbackUrl\":\"https://app.example.com/cb\",\"apiProducts\":[\"pets-gold\",\"premium\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.app.name").value("mobile"))
                .andExpect(jsonPath("$.app.credentials", hasSize(1)))
                .andExpect(jsonPath("$.credential.apiProducts[?(@.apiProduct == 'pets-gold')].status").value(hasItem("APPROVED")))
                .andExpect(jsonPath("$.credential.apiProducts[?(@.apiProduct == 'premium')].status").value(hasItem("PENDING")))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.credential.consumerKey");
        String secret = JsonPath.read(body, "$.credential.consumerSecret");
        String keyId = JsonPath.read(body, "$.credential.id");
        assertThat(key).hasSize(32);
        assertThat(secret).hasSize(48);
        assertThat(configVersion(org, "dev")).isGreaterThan(versionBefore);

        // Later reads never show the key or secret again.
        String app = mvc.perform(get("/api/v1/organizations/{org}/developers/ada@example.com/apps/mobile", org)
                        .with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials[0].keyPrefix").value(key.substring(0, 6)))
                .andReturn().getResponse().getContentAsString();
        assertThat(app).doesNotContain(key).doesNotContain(secret);

        // The gateway sees hashes, and only approved products offered in the environment.
        snapshot(org, "dev")
                .andExpect(jsonPath("$.credentials", hasSize(1)))
                .andExpect(jsonPath("$.credentials[0].keyHash").value(Hashing.sha256Hex(key)))
                .andExpect(jsonPath("$.credentials[0].secretHash").value(Hashing.sha256Hex(secret)))
                .andExpect(jsonPath("$.credentials[0].appName").value("mobile"))
                .andExpect(jsonPath("$.credentials[0].developerEmail").value("ada@example.com"))
                .andExpect(jsonPath("$.credentials[0].apiProducts").value(contains("pets-gold")))
                .andExpect(jsonPath("$.apiProducts[?(@.name == 'pets-gold')].quota.count").value(hasItem(10)));
        snapshot(org, "prod")
                .andExpect(jsonPath("$.apiProducts[*].name").value(contains("premium")))
                .andExpect(jsonPath("$.credentials[0].apiProducts", hasSize(0)));

        // Approving the pending product.
        String keyPath = "/api/v1/organizations/{org}/developers/ada@example.com/apps/mobile/keys/{keyId}";
        mvc.perform(patch(keyPath + "/apiproducts/premium", org, keyId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiProducts[?(@.apiProduct == 'premium')].status").value(hasItem("APPROVED")));
        snapshot(org, "dev").andExpect(jsonPath("$.credentials[0].apiProducts", hasSize(2)));

        // Rotation: a new credential with the same products; the old one keeps working for the grace period.
        MvcResult rotated = mvc.perform(post(keyPath + "/rotate", org, keyId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"graceSeconds\":3600}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credential.consumerKey").value(notNullValue()))
                .andExpect(jsonPath("$.credential.apiProducts", hasSize(2)))
                .andExpect(jsonPath("$.previous.expiresAt").value(notNullValue()))
                .andReturn();
        String newKey = JsonPath.read(rotated.getResponse().getContentAsString(), "$.credential.consumerKey");
        snapshot(org, "dev")
                .andExpect(jsonPath("$.credentials", hasSize(2)))
                .andExpect(jsonPath("$.credentials[*].keyHash").value(hasItem(Hashing.sha256Hex(newKey))));

        // Revoking the old key removes it from the feed.
        mvc.perform(patch(keyPath, org, keyId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        snapshot(org, "dev")
                .andExpect(jsonPath("$.credentials", hasSize(1)))
                .andExpect(jsonPath("$.credentials[0].keyHash").value(Hashing.sha256Hex(newKey)));
        mvc.perform(post(keyPath + "/rotate", org, keyId).with(person(alice)))
                .andExpect(status().isUnprocessableContent());

        // Revoked apps and inactive developers are withheld from gateways.
        mvc.perform(patch("/api/v1/organizations/{org}/developers/ada@example.com/apps/mobile", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\"}"))
                .andExpect(jsonPath("$.status").value("REVOKED"));
        snapshot(org, "dev").andExpect(jsonPath("$.credentials", hasSize(0)));
        mvc.perform(patch("/api/v1/organizations/{org}/developers/ada@example.com/apps/mobile", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/organizations/{org}/developers/ada@example.com", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        snapshot(org, "dev").andExpect(jsonPath("$.credentials", hasSize(0)));

        // Products held by credentials can't be deleted; the org-wide app list finds the app.
        mvc.perform(delete("/api/v1/organizations/{org}/apiproducts/pets-gold", org).with(person(alice)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("credential")));
        mvc.perform(get("/api/v1/organizations/{org}/apps", org).param("q", "ada@").with(person(alice)))
                .andExpect(jsonPath("$.items[0].name").value("mobile"))
                .andExpect(jsonPath("$.items[0].credentials", hasSize(2)));

        // Deleting the developer removes apps and credentials.
        mvc.perform(delete("/api/v1/organizations/{org}/developers/ada@example.com", org).with(person(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/organizations/{org}/apiproducts/pets-gold", org).with(person(alice)))
                .andExpect(status().isNoContent());
    }

    @Test
    void additionalKeysAndProductChangesPerCredential() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createProduct(org, "{\"name\":\"a\",\"proxies\":[\"pets\"]}", person(alice)).andExpect(status().isCreated());
        createProduct(org, "{\"name\":\"b\",\"proxies\":[\"orders\"]}", person(alice)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/developers", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bo@example.com\",\"firstName\":\"Bo\",\"lastName\":\"B\"}"))
                .andExpect(status().isCreated());
        String apps = "/api/v1/organizations/{org}/developers/bo@example.com/apps";
        mvc.perform(post(apps, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cli\",\"apiProducts\":[\"a\",\"missing\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("missing")));
        mvc.perform(post(apps, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cli\",\"apiProducts\":[\"a\"],\"keyExpiresInSeconds\":3600}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credential.expiresAt").value(notNullValue()));

        // A second key copies the products of the newest one by default.
        MvcResult second = mvc.perform(post(apps + "/cli/keys", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiProducts[0].apiProduct").value("a"))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andReturn();
        String keyId = JsonPath.read(second.getResponse().getContentAsString(), "$.id");

        mvc.perform(post(apps + "/cli/keys/{keyId}/apiproducts", org, keyId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiProduct\":\"b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiProducts", hasSize(2)));
        mvc.perform(post(apps + "/cli/keys/{keyId}/apiproducts", org, keyId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiProduct\":\"b\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(apps + "/cli/keys/{keyId}/apiproducts/a", org, keyId).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiProducts[*].apiProduct").value(contains("b")));
        snapshot(org, "dev").andExpect(jsonPath("$.credentials", hasSize(2)));

        mvc.perform(delete(apps + "/cli/keys/{keyId}", org, keyId).with(person(alice))).andExpect(status().isNoContent());
        mvc.perform(get(apps + "/cli/keys/{keyId}", org, keyId).with(person(alice))).andExpect(status().isNotFound());
        snapshot(org, "dev").andExpect(jsonPath("$.credentials", hasSize(1)));
    }

    // ------------------------------------------------------------------ helpers

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private ResultActions createProduct(String org, String json, RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/apiproducts", org).with(as)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions snapshot(String org, String env) throws Exception {
        return mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/{env}", org, env).with(gateway()))
                .andExpect(status().isOk());
    }

    private long configVersion(String org, String env) throws Exception {
        String json = snapshot(org, env).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.configVersion")).longValue();
    }
}
