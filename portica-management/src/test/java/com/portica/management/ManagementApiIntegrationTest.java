package com.portica.management;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of organizations, environments, members and RBAC against a real PostgreSQL
 * (Flyway migrations included). Tokens are simulated with spring-security-test.
 */
class ManagementApiIntegrationTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ authentication

    @Test
    void requestsWithoutTokenGetProblemDetails401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(header().exists("X-Portica-Request-Id"))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value(notNullValue()));
    }

    @Test
    void incomingRequestIdIsPropagated() throws Exception {
        mvc.perform(get("/api/v1/roles").with(person("rid")).header("X-Portica-Request-Id", "test-request-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Portica-Request-Id", "test-request-0001"));
    }

    // ------------------------------------------------------------------ organizations

    @Test
    void onlyPlatformAdminsCanCreateOrganizations() throws Exception {
        mvc.perform(post("/api/v1/organizations").with(person("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(uniqueName("org"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void creatingAnOrganizationBootstrapsEnvironmentsAndInvitedAdmin() throws Exception {
        String org = uniqueName("acme");
        String alice = uniqueName("alice");

        mvc.perform(post("/api/v1/organizations").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","displayName":"Acme Corp","adminEmail":"%s"}
                                """.formatted(org, email(alice))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsPath("/api/v1/organizations/" + org)))
                .andExpect(jsonPath("$.name").value(org))
                .andExpect(jsonPath("$.displayName").value("Acme Corp"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Alice signs in for the first time: her invitation is linked and she is ORG_ADMIN.
        mvc.perform(get("/api/v1/me").with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.memberships[0].organization").value(org))
                .andExpect(jsonPath("$.memberships[0].roles[0]").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.memberships[0].permissions", hasItem("MEMBER_MANAGE")));

        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].name", containsInAnyOrder("dev", "test", "prod")));

        mvc.perform(get("/api/v1/organizations/{org}/audit-events", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].action", hasItem("organization.created")));
    }

    @Test
    void duplicateOrganizationNameIsAConflict() throws Exception {
        String org = createOrganization(uniqueName("alice"));

        mvc.perform(post("/api/v1/organizations").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(org)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    void organizationListIsScopedToMembership() throws Exception {
        String alice = uniqueName("alice");
        String carol = uniqueName("carol");
        String aliceOrg = createOrganization(alice);
        String carolOrg = createOrganization(carol);

        mvc.perform(get("/api/v1/organizations").param("q", aliceOrg).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value(aliceOrg));

        mvc.perform(get("/api/v1/organizations").param("q", carolOrg).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/organizations/{org}", carolOrg).with(person(alice)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/organizations").param("q", carolOrg).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void archivedOrganizationLocksOutMembers() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(delete("/api/v1/organizations/{org}", org).with(person(alice)))
                .andExpect(status().isForbidden()); // only platform admins archive

        mvc.perform(delete("/api/v1/organizations/{org}", org).with(platformAdmin()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(person(alice)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/organizations/{org}", org).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    // ------------------------------------------------------------------ environments + RBAC

    @Test
    void permissionsAreEnforcedPerRole() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");
        String org = createOrganization(alice);

        // Bob is not a member yet.
        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(person(bob)))
                .andExpect(status().isForbidden());

        // Alice adds Bob as VIEWER. Bob's request above already provisioned him, so he is ACTIVE, not invited.
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"viewer\"]}".formatted(email(bob))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"));

        // Bob can read but not write.
        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(person(bob)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"staging\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/{org}/audit-events", org).with(person(bob)))
                .andExpect(status().isForbidden());

    }

    @Test
    void invitationIsLinkedOnFirstSignIn() throws Exception {
        String alice = uniqueName("alice");
        String carol = uniqueName("carol"); // never signed in before being added
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"VIEWER\"]}".formatted(email(carol))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"));

        // Carol's first request links the invitation to her identity and grants her access.
        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(person(carol)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/organizations/{org}/members", org).param("q", carol).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void invitationIsNotLinkedWhenEmailIsUnverified() throws Exception {
        String alice = uniqueName("alice");
        String eve = uniqueName("eve");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"ORG_ADMIN\"]}".formatted(email(eve))))
                .andExpect(status().isCreated());

        // Someone signs in claiming Eve's email but it is not verified: no access to the org.
        mvc.perform(get("/api/v1/organizations/{org}/environments", org).with(jwt().jwt(token -> token
                        .subject("sub-impostor-" + eve)
                        .claim("email", email(eve))
                        .claim("email_verified", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void environmentLifecycle() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"staging\",\"displayName\":\"Staging\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        containsPath("/api/v1/organizations/" + org + "/environments/staging")))
                .andExpect(jsonPath("$.createdBy").value(email(alice)));

        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"staging\"}"))
                .andExpect(status().isConflict());

        mvc.perform(patch("/api/v1/organizations/{org}/environments/staging", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Staging EU\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Staging EU"));

        mvc.perform(get("/api/v1/organizations/{org}/environments", org).param("q", "STAG").with(person(alice)))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(delete("/api/v1/organizations/{org}/environments/staging", org).with(person(alice)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/organizations/{org}/environments/staging", org).with(person(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/organizations/{org}/audit-events", org).with(person(alice)))
                .andExpect(jsonPath("$.items[*].action",
                        hasItem("environment.deleted")))
                .andExpect(jsonPath("$.items[0].action").value("environment.deleted"));
    }

    @Test
    void invalidInputIsReportedPerField() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad_Name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mvc.perform(get("/api/v1/organizations/{org}/environments", org).param("sort", "nope").with(person(alice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot sort by 'nope'. Allowed: createdAt, displayName, name, updatedAt"));
    }

    // ------------------------------------------------------------------ members

    @Test
    void lastOrgAdminCannotBeRemovedOrDemoted() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");
        String org = createOrganization(alice);
        String aliceId = userId(alice);

        mvc.perform(delete("/api/v1/organizations/{org}/members/{id}", org, aliceId).with(person(alice)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mvc.perform(put("/api/v1/organizations/{org}/members/{id}/roles", org, aliceId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"VIEWER\"]}"))
                .andExpect(status().isUnprocessableContent());

        // With a second admin, Alice can step down.
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"ORG_ADMIN\"]}".formatted(email(bob))))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/organizations/{org}/members/{id}/roles", org, aliceId).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"VIEWER\",\"ANALYST\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("VIEWER", "ANALYST")));

        // Alice no longer manages members.
        mvc.perform(delete("/api/v1/organizations/{org}/members/{id}", org, aliceId).with(person(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void addingMembersValidatesRolesAndDuplicates() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");
        String org = createOrganization(alice);

        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"SUPERUSER\"]}".formatted(email(bob))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown role(s): SUPERUSER"));

        String body = "{\"email\":\"%s\",\"roles\":[\"VIEWER\"]}".formatted(email(bob));
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void rolesCatalogIsAvailableToAnyAuthenticatedUser() throws Exception {
        mvc.perform(get("/api/v1/roles").with(person(uniqueName("dave"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code",
                        containsInAnyOrder("ANALYST", "API_ADMIN", "BUSINESS_USER", "OPS_ADMIN", "ORG_ADMIN", "VIEWER")))
                .andExpect(jsonPath("$[?(@.code == 'VIEWER')].permissions[*]", hasItem("ENV_READ")));
    }
}
