package com.portica.management;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 4: key value maps (plain and encrypted, environment and organization scope) and their deploy checks. */
class KeyValueMapIntegrationTest extends AbstractIntegrationTest {

    @Test
    void plainAndEncryptedMapsReachTheGatewayButSecretsNeverLeaveTheApi() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        String envMaps = "/api/v1/organizations/{org}/environments/dev/keyvaluemaps";

        mvc.perform(post(envMaps, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"config\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("environment"))
                .andExpect(jsonPath("$.encrypted").value(false));
        mvc.perform(post(envMaps, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"config\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(envMaps, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"secrets\",\"encrypted\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/keyvaluemaps", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"shared\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("organization"));

        putEntry(envMaps + "/config/entries/backend.url", org, "https://backend.internal", person(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("https://backend.internal"));
        putEntry(envMaps + "/secrets/entries/api-token", org, "t0p-s3cret", person(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("*****"));
        putEntry("/api/v1/organizations/{org}/keyvaluemaps/shared/entries/region", org, "eu", person(alice))
                .andExpect(status().isOk());
        putEntry(envMaps + "/config/entries/bad key!", org, "x", person(alice)).andExpect(status().isBadRequest());

        String listed = mvc.perform(get(envMaps + "/secrets/entries", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("api-token"))
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("t0p-s3cret");
        mvc.perform(get(envMaps, org).with(person(alice)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.name == 'secrets')].entryCount").value(hasItem(1)));

        // Gateways receive the decrypted value, and organization maps in every environment.
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.keyValueMaps", hasSize(3)))
                .andExpect(jsonPath("$.keyValueMaps[?(@.name == 'secrets')].entries['api-token']").value(hasItem("t0p-s3cret")))
                .andExpect(jsonPath("$.keyValueMaps[?(@.name == 'shared')].scope").value(hasItem("organization")));

        mvc.perform(delete(envMaps + "/config/entries/backend.url", org).with(person(alice))).andExpect(status().isNoContent());
        mvc.perform(get(envMaps + "/config/entries/backend.url", org).with(person(alice))).andExpect(status().isNotFound());
        mvc.perform(delete(envMaps + "/config", org).with(person(alice))).andExpect(status().isNoContent());
    }

    @Test
    void deploymentsRequireTheMapsTheirPoliciesRead() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"pets","revision":{"basePath":"/pets","target":{"url":"https://pets.example.com"},
                                 "policies":[{"name":"kvm","type":"KeyValueMapOperations",
                                              "config":{"mapName":"secrets","get":[{"key":"token","assignTo":"backend.token"}]}}],
                                 "flows":{"preFlow":{"request":[{"policy":"kvm"}]}}}}
                                """))
                .andExpect(status().isCreated());

        deploy(org, "pets", 1, person(alice))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("does not exist")));

        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/keyvaluemaps", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"secrets\",\"encrypted\":true}"))
                .andExpect(status().isCreated());
        deploy(org, "pets", 1, person(alice))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("private.")));

        mvc.perform(post("/api/v1/organizations/{org}/apis/pets/revisions", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basePath":"/pets","target":{"url":"https://pets.example.com"},
                                 "policies":[{"name":"kvm","type":"KeyValueMapOperations",
                                              "config":{"mapName":"secrets","get":[{"key":"token","assignTo":"private.backend.token"}]}}],
                                 "flows":{"preFlow":{"request":[{"policy":"kvm"}]}}}
                                """))
                .andExpect(status().isCreated());
        deploy(org, "pets", 2, person(alice)).andExpect(status().isCreated());
    }

    @Test
    void gatewayOAuthPathsAreReserved() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"tokens\",\"revision\":{\"basePath\":\"/oauth2/custom\",\"target\":{\"url\":\"https://x.example.com\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("reserved")));
    }

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private ResultActions putEntry(String path, String org, String value, RequestPostProcessor as) throws Exception {
        return mvc.perform(put(path, org).with(as).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"%s\"}".formatted(value)));
    }

    private ResultActions deploy(String org, String api, int rev, RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/{api}/revisions/{rev}/deployments",
                org, api, rev).with(as));
    }
}
