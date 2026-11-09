package com.portica.management;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 developer portal API: public catalog with docs, self-registration, apps limited to public
 * products and to the calling developer, and the service-account guard.
 */
class PortalIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Pet Store, version: 1.2.0 }
            servers: [ { url: https://pets.example.com } ]
            paths:
              /pets:
                get: { operationId: listPets, summary: List pets }
                post: { operationId: addPet, summary: Add a pet }
            """;

    @Test
    void developersDiscoverPublicApisAndManageTheirOwnApps() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"dev\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("name", "pets", "spec", SPEC))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/pets/revisions/1/deployments", org)
                .with(person(alice))).andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/v1/organizations/{org}/apiproducts", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pets-free\",\"proxies\":[\"pets\"],\"quota\":{\"count\":100,\"timeUnit\":\"hour\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/apiproducts", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"partners\",\"access\":\"PRIVATE\",\"proxies\":[\"pets\"]}"))
                .andExpect(status().isCreated());
        String base = "/internal/v1/portal/organizations/{org}";

        // only the portal service account may call; people (even org admins) may not
        mvc.perform(get(base + "/catalog", org).with(person(alice))).andExpect(status().isForbidden());

        mvc.perform(get(base + "/catalog", org).with(portal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[*].name", contains("pets-free")))
                .andExpect(jsonPath("$.products[0].quota").value("100 per hour"))
                .andExpect(jsonPath("$.apis[0].name").value("pets"))
                .andExpect(jsonPath("$.apis[0].documented").value(true))
                .andExpect(jsonPath("$.apis[0].deployments[0].environment").value("dev"))
                .andExpect(jsonPath("$.apis[0].deployments[0].url").value(startsWith("http")));
        mvc.perform(get(base + "/apis/pets/environments/dev/docs", org).with(portal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Pet Store"))
                .andExpect(jsonPath("$.operations", hasSize(2)))
                .andExpect(jsonPath("$.openApiSpec").value(startsWith("openapi")));
        mvc.perform(get(base + "/apis/pets/environments/prod/docs", org).with(portal())).andExpect(status().isNotFound());

        // self-registration, then an app with a public product
        String ada = "ada-" + uniqueName("x") + "@example.com";
        mvc.perform(as(get(base + "/me", org), ada)).andExpect(status().isNotFound());
        mvc.perform(as(post(base + "/me", org), ada).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(ada))
                .andExpect(jsonPath("$.attributes.registeredVia").value("portal"));
        mvc.perform(as(post(base + "/me/apps", org), ada).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sneaky\",\"apiProducts\":[\"partners\"]}"))
                .andExpect(status().isNotFound());
        String created = mvc.perform(as(post(base + "/me/apps", org), ada).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mobile\",\"apiProducts\":[\"pets-free\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.credential.consumerKey").exists())
                .andExpect(jsonPath("$.app.credentials", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String keyId = JsonPath.read(created, "$.credential.id");
        mvc.perform(as(get(base + "/me/apps", org), ada))
                .andExpect(jsonPath("$[*].name", contains("mobile")));

        // another developer sees none of it
        String bob = "bob-" + uniqueName("x") + "@example.com";
        mvc.perform(as(post(base + "/me", org), bob).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Bob\",\"lastName\":\"B\"}")).andExpect(status().isCreated());
        mvc.perform(as(get(base + "/me/apps", org), bob)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(as(get(base + "/me/apps/mobile", org), bob)).andExpect(status().isNotFound());
        mvc.perform(as(delete(base + "/me/apps/mobile/keys/{key}", org, keyId), bob)).andExpect(status().isNotFound());

        mvc.perform(as(post(base + "/me/apps/mobile/keys", org), ada).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiProducts[0].apiProduct").value("pets-free"));
        mvc.perform(as(delete(base + "/me/apps/mobile/keys/{key}", org, keyId), ada)).andExpect(status().isNoContent());
        mvc.perform(as(delete(base + "/me/apps/mobile", org), ada)).andExpect(status().isNoContent());
    }

    /** The developer portal's service account. */
    private static RequestPostProcessor portal() {
        return jwt().jwt(token -> token.subject("service-account-portica-portal"))
                .authorities(new SimpleGrantedAuthority("REALM_portica-portal"));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String developer) {
        return request.with(portal()).header("X-Portica-Developer", developer);
    }
}
