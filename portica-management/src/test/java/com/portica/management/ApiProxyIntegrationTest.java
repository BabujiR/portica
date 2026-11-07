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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2: target servers, API proxies, revisions, OpenAPI import, deployments and the runtime feed.
 */
class ApiProxyIntegrationTest extends AbstractIntegrationTest {

    private static final String PETSTORE_YAML = """
            openapi: 3.0.3
            info:
              title: Swagger Petstore
              version: 1.0.7
              description: Sample pet store
            servers:
              - url: https://{region}.petstore.example.com
              - url: https://petstore.example.com/api/v3/
            paths:
              /pet/{petId}:
                parameters:
                  - name: petId
                    in: path
                    required: true
                    schema: { type: integer }
                get:
                  operationId: getPetById
                  summary: Find pet by ID
                delete:
                  operationId: deletePet
            """;

    // ----------------------------------------------------------------- full lifecycle

    @Test
    void proxyLifecycleFromCreateToDeployToUndeploy() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));

        // Revision 1 targets a URL directly.
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"orders","displayName":"Orders",
                                 "revision":{"basePath":"/orders","target":{"url":"https://api.example.com/v2/"},
                                             "description":"first"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsPath("/api/v1/organizations/" + org + "/apis/orders")))
                .andExpect(jsonPath("$.apiProxy.latestRevision").value(1))
                .andExpect(jsonPath("$.revision.revision").value(1))
                .andExpect(jsonPath("$.revision.target.url").value("https://api.example.com"))
                .andExpect(jsonPath("$.revision.target.path").value("/v2"))
                .andExpect(jsonPath("$.revision.connectTimeoutMs").value(5000));

        // Revision 2 targets a named target server.
        mvc.perform(post("/api/v1/organizations/{org}/apis/orders/revisions", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"basePath":"/orders","target":{"server":"orders-backend","path":"/api"},
                                 "readTimeoutMs":10000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.target.server").value("orders-backend"));

        // Deploy r1; deploying it again is an idempotent 200.
        deploy(org, "dev", "orders", 1, person(alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gatewayPath").value("/" + org + "/dev/orders"));
        deploy(org, "dev", "orders", 1, person(alice)).andExpect(status().isOk());

        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proxies[0].name").value("orders"))
                .andExpect(jsonPath("$.proxies[0].revision").value(1))
                .andExpect(jsonPath("$.proxies[0].targetUri").value("https://api.example.com"))
                .andExpect(jsonPath("$.proxies[0].targetPath").value("/v2"));

        // r2 needs its target server in dev.
        deploy(org, "dev", "orders", 2, person(alice))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("orders-backend")));

        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/target-servers", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"orders-backend","host":"orders.dev.internal","port":8080,"protocol":"HTTP"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseUri").value("http://orders.dev.internal:8080"));

        // Swap r1 -> r2 in place.
        deploy(org, "dev", "orders", 2, person(alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.previousRevision").value(1));

        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.proxies.length()").value(1))
                .andExpect(jsonPath("$.proxies[0].revision").value(2))
                .andExpect(jsonPath("$.proxies[0].targetUri").value("http://orders.dev.internal:8080"))
                .andExpect(jsonPath("$.proxies[0].targetPath").value("/api"))
                .andExpect(jsonPath("$.proxies[0].readTimeoutMs").value(10000))
                .andExpect(jsonPath("$.configVersion").value(greaterThan(1)));

        // Live resources are protected.
        mvc.perform(delete("/api/v1/organizations/{org}/environments/dev/target-servers/orders-backend", org)
                        .with(person(alice)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/organizations/{org}/apis/orders/revisions/2", org).with(person(alice)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/organizations/{org}/apis/orders", org).with(person(alice)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/organizations/{org}/environments/dev", org).with(person(alice)))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/v1/organizations/{org}/apis/orders/deployments", org).with(person(alice)))
                .andExpect(jsonPath("$[0].environment").value("dev"))
                .andExpect(jsonPath("$[0].revision").value(2));

        // Undeploy: only the live revision can be undeployed.
        undeploy(org, "dev", "orders", 1, person(alice)).andExpect(status().isNotFound());
        undeploy(org, "dev", "orders", 2, person(alice)).andExpect(status().isNoContent());

        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.proxies.length()").value(0));

        // Nothing live any more: revisions and the proxy can be deleted.
        mvc.perform(delete("/api/v1/organizations/{org}/apis/orders/revisions/1", org).with(person(alice)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/organizations/{org}/apis/orders", org).with(person(alice)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/organizations/{org}/audit-events", org).with(person(alice)))
                .andExpect(jsonPath("$.items[*].action", hasItem("api-proxy.deployed")))
                .andExpect(jsonPath("$.items[*].action", hasItem("api-proxy.undeployed")))
                .andExpect(jsonPath("$.items[*].action", hasItem("target-server.created")));
    }

    @Test
    void basePathsAreUniquePerEnvironment() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createProxy(org, "first", "/shared", person(alice));
        createProxy(org, "second", "/shared", person(alice));

        deploy(org, "dev", "first", 1, person(alice)).andExpect(status().isCreated());
        deploy(org, "dev", "second", 1, person(alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("first")));
    }

    // ----------------------------------------------------------------- OpenAPI import

    @Test
    void importingOpenApiCreatesProxyThenNewRevisions() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        String body = """
                {"name":"petstore","spec":%s}
                """.formatted(jsonString(PETSTORE_YAML));
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdApiProxy").value(true))
                .andExpect(jsonPath("$.apiProxy.displayName").value("Swagger Petstore"))
                .andExpect(jsonPath("$.revision.revision").value(1))
                .andExpect(jsonPath("$.revision.basePath").value("/petstore"))
                // templated server skipped; first absolute one used, trailing slash dropped
                .andExpect(jsonPath("$.revision.target.url").value("https://petstore.example.com"))
                .andExpect(jsonPath("$.revision.target.path").value("/api/v3"))
                .andExpect(jsonPath("$.specVersion").value("3.0.3"))
                .andExpect(jsonPath("$.operationCount").value(2));

        // Re-import adds a revision to the same proxy.
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdApiProxy").value(false))
                .andExpect(jsonPath("$.revision.revision").value(2));

        mvc.perform(get("/api/v1/organizations/{org}/apis/petstore/revisions/2/operations", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations[0].method").value("GET"))
                .andExpect(jsonPath("$.operations[0].operationId").value("getPetById"))
                .andExpect(jsonPath("$.operations[1].method").value("DELETE"));

        mvc.perform(get("/api/v1/organizations/{org}/apis/petstore/revisions/1/openapi", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("getPetById")));
    }

    @Test
    void importRejectsInvalidSpecsAndSpecsWithoutTarget() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"spec\":\"just: [unclosed\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"spec\":\"title: not openapi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("OpenAPI 3.x")));

        String noServers = "openapi: 3.1.0\ninfo:\n  title: Internal\n  version: '1'\npaths: {}\n";
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"internal\",\"spec\":%s}".formatted(jsonString(noServers))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("target.url")));

        // ...but works when a target is supplied.
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"internal\",\"target\":{\"server\":\"internal-svc\"},\"spec\":%s}"
                                .formatted(jsonString(noServers))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision.target.server").value("internal-svc"));
    }

    // ----------------------------------------------------------------- validation and RBAC

    @Test
    void invalidTargetsAndBasePathsAreRejected() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        String[] badRevisions = {
                "{\"basePath\":\"/x\",\"target\":{\"url\":\"https://a.example.com\",\"server\":\"s\"}}",
                "{\"basePath\":\"/x\",\"target\":{}}",
                "{\"basePath\":\"/x\",\"target\":{\"url\":\"ftp://a.example.com\"}}",
                "{\"basePath\":\"/x\",\"target\":{\"url\":\"https://user:pw@a.example.com\"}}",
                "{\"basePath\":\"/x\",\"target\":{\"url\":\"https://a.example.com/?q=1\"}}",
                "{\"basePath\":\"x\",\"target\":{\"url\":\"https://a.example.com\"}}",
                "{\"basePath\":\"/x/\",\"target\":{\"url\":\"https://a.example.com\"}}"
        };
        for (String revision : badRevisions) {
            mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(person(alice))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"bad\",\"revision\":%s}".formatted(revision)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rolesControlWhoCanBuildAndWhoCanDeploy() throws Exception {
        String alice = uniqueName("alice");
        String ops = uniqueName("ops");
        String viewer = uniqueName("viewer");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        addMember(org, ops, "OPS_ADMIN", person(alice));
        addMember(org, viewer, "VIEWER", person(alice));
        createProxy(org, "billing", "/billing", person(alice));

        // OPS_ADMIN deploys but cannot change proxies.
        mvc.perform(post("/api/v1/organizations/{org}/apis/billing/revisions", org).with(person(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePath\":\"/billing\",\"target\":{\"url\":\"https://b.example.com\"}}"))
                .andExpect(status().isForbidden());
        deploy(org, "dev", "billing", 1, person(ops)).andExpect(status().isCreated());

        // VIEWER reads only.
        mvc.perform(get("/api/v1/organizations/{org}/apis/billing", org).with(person(viewer)))
                .andExpect(status().isOk());
        undeploy(org, "dev", "billing", 1, person(viewer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/organizations/{org}/environments/dev/target-servers/x", org).with(person(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"x.internal\",\"port\":80,\"protocol\":\"HTTP\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void runtimeFeedIsOnlyForGatewaysAndPlatformAdmins() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));

        mvc.perform(get("/internal/v1/runtime/environments").with(person(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/runtime/environments").with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environments[?(@.organization == '%s')].environment".formatted(org),
                        hasItem("dev")));
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/nope", org).with(gateway()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/runtime/environments").with(platformAdmin()))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------------------- helpers

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private void createProxy(String org, String name, String basePath, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","revision":{"basePath":"%s","target":{"url":"https://backend.example.com"}}}
                                """.formatted(name, basePath)))
                .andExpect(status().isCreated());
    }

    private void addMember(String org, String person, String role, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"%s\"]}".formatted(email(person), role)))
                .andExpect(status().isCreated());
    }

    private ResultActions deploy(String org, String env, String api, int rev,
                                                                       RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/environments/{env}/apis/{api}/revisions/{rev}/deployments",
                org, env, api, rev).with(as));
    }

    private ResultActions undeploy(String org, String env, String api, int rev,
                                                                         RequestPostProcessor as) throws Exception {
        return mvc.perform(delete("/api/v1/organizations/{org}/environments/{env}/apis/{api}/revisions/{rev}/deployments",
                org, env, api, rev).with(as));
    }

    /** Encodes text as a JSON string literal. */
    private static String jsonString(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
