package com.portica.management;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3: policy bundles on proxy revisions, shared flows and their deployment rules, the
 * runtime feed carrying policies, and the policy type catalog.
 */
class PolicyBundleIntegrationTest extends AbstractIntegrationTest {

    private static final String PROXY_WITH_POLICIES = """
            {"name":"pets","revision":{"basePath":"/pets","target":{"url":"https://pets.example.com"},
             "policies":[
               {"name":"verify-key","type":"VerifyAPIKey","config":{}},
               {"name":"quota","type":"Quota","config":{"count":100,"interval":1,"timeUnit":"minute",
                                                         "identifier":"{verifyapikey.verify-key.client_id}"}},
               {"name":"call-security","type":"FlowCallout","config":{"sharedFlow":"security"}}],
             "flows":{"preFlow":{"request":[{"policy":"verify-key"},{"policy":"quota"},{"policy":"call-security"}]},
                      "conditionalFlows":[{"name":"get","condition":"request.verb == \\"GET\\"","request":[],"response":[]}]}}}
            """;

    @Test
    void invalidBundlesAreRejectedWithEveryProblemListed() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"broken","revision":{"basePath":"/broken","target":{"url":"https://x.example.com"},
                                 "policies":[
                                   {"name":"q","type":"Quota","config":{"count":-1,"unknownField":true}},
                                   {"name":"q","type":"SpikeArrest","config":{"rate":"10ps"}},
                                   {"name":"x","type":"NoSuchPolicy","config":{}}],
                                 "flows":{"preFlow":{"request":[{"policy":"missing"},
                                                                {"policy":"q","condition":"request.verb =="}]}}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(greaterThan(3)))
                .andExpect(jsonPath("$.errors").value(hasItem(containsString("missing"))))
                .andExpect(jsonPath("$.errors").value(hasItem(containsString("NoSuchPolicy"))));

        // Nothing was created.
        mvc.perform(get("/api/v1/organizations/{org}/apis/broken", org).with(person(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revisionsStorePoliciesAndFlows() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(PROXY_WITH_POLICIES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision.policies.length()").value(3))
                .andExpect(jsonPath("$.revision.policies[1].config.count").value(100))
                .andExpect(jsonPath("$.revision.flows.preFlow.request[2].policy").value("call-security"))
                .andExpect(jsonPath("$.revision.flows.conditionalFlows[0].name").value("get"));

        mvc.perform(get("/api/v1/organizations/{org}/apis/pets/revisions/1", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies[0].type").value("VerifyAPIKey"));
    }

    @Test
    void sharedFlowsDeployInDependencyOrderAndFeedTheGateway() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));

        createSharedFlow(org, "cors-headers", """
                {"policies":[{"name":"cors","type":"CORS","config":{"allowOrigins":["*"]}}],
                 "steps":[{"policy":"cors"}]}
                """, person(alice));
        createSharedFlow(org, "security", """
                {"policies":[{"name":"spike","type":"SpikeArrest","config":{"rate":"50ps"}},
                             {"name":"call-cors","type":"FlowCallout","config":{"sharedFlow":"cors-headers"}}],
                 "steps":[{"policy":"spike"},{"policy":"call-cors"}]}
                """, person(alice));
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(PROXY_WITH_POLICIES))
                .andExpect(status().isCreated());

        // The proxy needs "security", which needs "cors-headers".
        deployProxy(org, "pets", 1, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("security")));
        deploySharedFlow(org, "security", 1, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("cors-headers")));

        deploySharedFlow(org, "cors-headers", 1, person(alice)).andExpect(status().isCreated());
        deploySharedFlow(org, "security", 1, person(alice)).andExpect(status().isCreated());
        deploySharedFlow(org, "security", 1, person(alice)).andExpect(status().isOk()); // idempotent
        deployProxy(org, "pets", 1, person(alice)).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/organizations/{org}/environments/dev/sharedflow-deployments", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // The gateway snapshot carries the bundle and the shared flows.
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proxies[0].policies.length()").value(3))
                .andExpect(jsonPath("$.proxies[0].flows.preFlow.request[0].policy").value("verify-key"))
                .andExpect(jsonPath("$.sharedFlows.length()").value(2))
                .andExpect(jsonPath("$.sharedFlows[?(@.name == 'security')].steps[*].policy").value(hasItem("call-cors")));

        // A shared flow in use cannot be undeployed; neither can the environment be deleted.
        undeploySharedFlow(org, "cors-headers", 1, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("security")));
        undeploySharedFlow(org, "security", 1, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("pets")));

        // Revision 2 of cors-headers calling back into security would create a cycle.
        mvc.perform(post("/api/v1/organizations/{org}/sharedflows/cors-headers/revisions", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policies":[{"name":"back","type":"FlowCallout","config":{"sharedFlow":"security"}}],
                                 "steps":[{"policy":"back"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2));
        deploySharedFlow(org, "cors-headers", 2, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("cycle")));

        // Undeploying in reverse order works.
        mvc.perform(delete("/api/v1/organizations/{org}/environments/dev/apis/pets/revisions/1/deployments", org)
                .with(person(alice))).andExpect(status().isNoContent());
        undeploySharedFlow(org, "security", 1, person(alice)).andExpect(status().isNoContent());
        undeploySharedFlow(org, "cors-headers", 1, person(alice)).andExpect(status().isNoContent());
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.sharedFlows.length()").value(0));
    }

    @Test
    void sharedFlowsAreReadOnlyForViewers() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createSharedFlow(org, "common", """
                {"policies":[{"name":"spike","type":"SpikeArrest","config":{"rate":"10ps"}}],"steps":[{"policy":"spike"}]}
                """, person(alice));

        // An authenticated user who is not a member sees nothing.
        String mallory = uniqueName("mallory");
        mvc.perform(get("/api/v1/organizations/{org}/sharedflows/common", org).with(person(mallory)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/{org}/sharedflows/common", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRevision").value(1));
    }

    @Test
    void policyTypeCatalogListsEveryPolicy() throws Exception {
        mvc.perform(get("/api/v1/policy-types").with(person(uniqueName("bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(16))
                .andExpect(jsonPath("$[?(@.type == 'Quota')].configFields[*]").value(hasItem("identifier")))
                .andExpect(jsonPath("$[?(@.type == 'VerifyAPIKey')].category").value(hasItem("Security")));
        mvc.perform(get("/api/v1/policy-types")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private void createSharedFlow(String org, String name, String revisionJson, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/sharedflows", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"revision\":%s}".formatted(name, revisionJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sharedFlow.name").value(name))
                .andExpect(jsonPath("$.revision.revision").value(1));
    }

    private ResultActions deployProxy(String org, String api, int rev, RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/{api}/revisions/{rev}/deployments",
                org, api, rev).with(as));
    }

    private ResultActions deploySharedFlow(String org, String name, int rev, RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/environments/dev/sharedflows/{name}/revisions/{rev}/deployments",
                org, name, rev).with(as));
    }

    private ResultActions undeploySharedFlow(String org, String name, int rev, RequestPostProcessor as) throws Exception {
        return mvc.perform(delete("/api/v1/organizations/{org}/environments/dev/sharedflows/{name}/revisions/{rev}/deployments",
                org, name, rev).with(as));
    }
}
