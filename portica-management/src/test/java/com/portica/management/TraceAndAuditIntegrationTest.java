package com.portica.management;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 6: trace sessions (start, runtime feed, stop, limits, permissions), analytics errors and audit search. */
class TraceAndAuditIntegrationTest extends AbstractIntegrationTest {

    @Test
    void traceSessionsReachGatewaysUntilStopped() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createProxy(org, "pets", person(alice));
        String sessions = "/api/v1/organizations/{org}/environments/dev/apis/pets/trace-sessions";

        mvc.perform(post(sessions, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("not deployed")));
        deploy(org, "pets", person(alice));

        mvc.perform(post(sessions, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filterHeader\":\"bad header\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(sessions, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationSeconds\":3600}"))
                .andExpect(status().isBadRequest());

        String started = mvc.perform(post(sessions, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filterHeader\":\"X-Debug\",\"filterValue\":\"on\",\"durationSeconds\":120,\"maxTransactions\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.apiProxy").value("pets"))
                .andExpect(jsonPath("$.environment").value("dev"))
                .andExpect(jsonPath("$.maxTransactions").value(5))
                .andExpect(jsonPath("$.createdBy").value(email(alice)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(started, "$.id");

        mvc.perform(get("/internal/v1/runtime/trace-sessions").with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].organization".formatted(id)).value(hasItem(org)))
                .andExpect(jsonPath("$[?(@.id == '%s')].filterHeader".formatted(id)).value(hasItem("X-Debug")))
                .andExpect(jsonPath("$[?(@.id == '%s')].maxTransactions".formatted(id)).value(hasItem(5)));
        mvc.perform(get("/internal/v1/runtime/trace-sessions").with(person(alice))).andExpect(status().isForbidden());

        mvc.perform(get(sessions, org).with(person(alice)))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/organizations/{org}/trace-sessions/{id}", org, id).with(person(alice)))
                .andExpect(jsonPath("$.filterValue").value("on"));

        // two more fit, a fourth concurrent session doesn't
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(sessions, org).with(person(alice))).andExpect(status().isCreated());
        }
        mvc.perform(post(sessions, org).with(person(alice))).andExpect(status().isUnprocessableContent());

        mvc.perform(delete("/api/v1/organizations/{org}/trace-sessions/{id}", org, id).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
        mvc.perform(get("/internal/v1/runtime/trace-sessions").with(gateway()))
                .andExpect(jsonPath("$[*].id", not(hasItem(id))));

        // captured calls live in ClickHouse, which these tests don't run
        mvc.perform(get("/api/v1/organizations/{org}/trace-sessions/{id}/transactions", org, id).with(person(alice)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/organizations/{org}/trace-sessions/{id}", org, "00000000-0000-0000-0000-000000000000")
                        .with(person(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    void analystsReadAnalyticsButCannotTrace() throws Exception {
        String alice = uniqueName("alice");
        String ann = uniqueName("ann");
        String outsider = uniqueName("outsider");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createProxy(org, "pets", person(alice));
        deploy(org, "pets", person(alice));
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"ANALYST\"]}".formatted(email(ann))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/pets/trace-sessions", org).with(person(ann)))
                .andExpect(status().isForbidden());
        // allowed, but the store is down in tests
        mvc.perform(get("/api/v1/organizations/{org}/analytics/summary", org).with(person(ann)))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/organizations/{org}/analytics/summary", org).with(person(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/{org}/analytics/top", org).param("dimension", "client_ip; DROP")
                        .with(person(ann)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/organizations/{org}/analytics/events", org)
                        .param("from", "2026-01-02T00:00:00Z").param("to", "2026-01-01T00:00:00Z")
                        .with(person(ann)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auditLogCanBeFilteredByTextTypeAndTime() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        createProxy(org, "pets", person(alice));
        createProxy(org, "orders", person(alice));
        String audit = "/api/v1/organizations/{org}/audit-events";

        mvc.perform(get(audit, org).with(person(alice)).param("q", "orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].resourceName", hasItem("orders")))
                .andExpect(jsonPath("$.items[*].resourceName", not(hasItem("pets"))));
        mvc.perform(get(audit, org).with(person(alice)).param("resourceType", "Environment"))
                .andExpect(jsonPath("$.items[*].resourceType", not(hasItem("ApiProxy"))))
                .andExpect(jsonPath("$.items[0].resourceName").value("dev"));
        mvc.perform(get(audit, org).with(person(alice)).param("q", email(alice).substring(0, 6).toUpperCase()))
                .andExpect(jsonPath("$.items[*].resourceName", hasItem("pets")));
        mvc.perform(get(audit, org).with(person(alice)).param("to", "2001-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(audit, org).with(person(alice)).param("q", "100%"))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(audit, org).with(person(alice))
                        .param("from", "2030-01-01T00:00:00Z").param("to", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(audit + "/resource-types", org).with(person(alice)))
                .andExpect(jsonPath("$", hasItem("ApiProxy")))
                .andExpect(jsonPath("$", hasItem("Environment")));
    }

    // ------------------------------------------------------------------ helpers

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private void createProxy(String org, String name, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","revision":{"basePath":"/%s","target":{"url":"https://backend.example.com"}}}
                                """.formatted(name, name)))
                .andExpect(status().isCreated());
    }

    private void deploy(String org, String api, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/{api}/revisions/1/deployments", org, api)
                        .with(as))
                .andExpect(status().is2xxSuccessful());
    }
}
