package com.portica.management;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 management API: LLM providers and AI proxies, MCP servers (OpenAPI and remote with a bearer
 * token or OAuth client credentials), the runtime feeds the AI and MCP services read, and the generated
 * gateway proxies that put AI and MCP traffic through the policy engine.
 */
class AiAndMcpIntegrationTest extends AbstractIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static HttpServer authServer;
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static final List<String> tokenForms = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startAuthorizationServer() throws IOException {
        authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + authServer.getAddress().getPort();
        authServer.createContext("/.well-known/oauth-authorization-server", ex -> send(ex, 200, """
                {"issuer":"%s","token_endpoint":"%s/token","authorization_endpoint":"%s/authorize",
                 "code_challenge_methods_supported":["S256"]}""".formatted(issuer, issuer, issuer)));
        authServer.createContext("/token", ex -> {
            tokenRequests.incrementAndGet();
            tokenForms.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + "|" + ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, 200, "{\"access_token\":\"upstream-at-1\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"repo\"}");
        });
        authServer.start();
    }

    @AfterAll
    static void stopAuthorizationServer() {
        authServer.stop(0);
    }

    @Test
    void aiProvidersAndProxiesReachTheRuntimeFeeds() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        String ai = "/api/v1/organizations/{org}/ai";

        mvc.perform(post(ai + "/providers", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"openai","type":"OPENAI","apiKey":"sk-test-0123456789abcd",
                         "models":[{"name":"gpt-4o-mini","inputCostPerMillion":0.15,"outputCostPerMillion":0.60}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseUrl").value("https://api.openai.com/v1"))
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(jsonPath("$.apiKeyHint").value("…abcd"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(post(ai + "/providers", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"anthropic","type":"ANTHROPIC","apiKey":"sk-ant-0123456789",
                         "models":[{"name":"claude-haiku","inputCostPerMillion":1,"outputCostPerMillion":5}]}"""))
                .andExpect(status().isCreated());

        mvc.perform(post(ai + "/proxies", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"assistant","routes":[{"name":"fast","targets":[{"provider":"openai","model":"gpt-5"}]}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("gpt-5")));
        mvc.perform(post(ai + "/proxies", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"assistant","routes":[{"name":"fast","targets":[{"provider":"openai","model":"gpt-4o-mini"},
                                                                         {"provider":"anthropic","model":"claude-haiku"}]}],
                         "tokenQuota":{"tokens":100000,"timeUnit":"day"},"pii":{"action":"REDACT","types":["EMAIL"]}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultRoute").value("fast"))
                .andExpect(jsonPath("$.gatewayProxy").value("ai-assistant"))
                .andExpect(jsonPath("$.gatewayPath").value("/ai/assistant/v1"))
                .andExpect(jsonPath("$.tokenQuota.interval").value(1));

        // the AI gateway's feed carries the provider keys in plain text (runtime clients only)
        String feed = "/internal/v1/runtime/organizations/{org}/environments/dev/ai";
        mvc.perform(get(feed, org).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[?(@.name == 'openai')].apiKey").value(hasItem("sk-test-0123456789abcd")))
                .andExpect(jsonPath("$.proxies[0].name").value("assistant"))
                .andExpect(jsonPath("$.proxies[0].routes[0].targets[1].provider").value("anthropic"))
                .andExpect(jsonPath("$.proxies[0].pii.action").value("REDACT"));
        mvc.perform(get(feed, org).with(person(alice))).andExpect(status().isForbidden());

        // the gateway gets a generated proxy that verifies the app's key before forwarding to the AI gateway
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.proxies[?(@.name == 'ai-assistant')].basePath").value(hasItem("/ai/assistant")))
                .andExpect(jsonPath("$.proxies[?(@.name == 'ai-assistant')].targetPath")
                        .value(hasItem("/internal/ai/" + org + "/dev/assistant")))
                .andExpect(jsonPath("$.proxies[?(@.name == 'ai-assistant')].policies[0].type").value(hasItem("VerifyAPIKey")));

        // names stay unique at the gateway, and providers in use can't go
        createProxy(org, "ai-assistant", "/other", person(alice)).andExpect(status().isConflict());
        mvc.perform(delete(ai + "/providers/anthropic", org).with(person(alice)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put(ai + "/providers/anthropic", org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"models\":[{\"name\":\"claude-sonnet\",\"inputCostPerMillion\":3,\"outputCostPerMillion\":15}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("claude-haiku")));
        mvc.perform(get("/api/v1/organizations/{org}/audit-events", org).with(person(alice)).param("q", "ai-provider"))
                .andExpect(jsonPath("$.items[*].action", hasItem("ai-provider.created")));
    }

    @Test
    void openApiMcpServersGenerateToolsFromTheDeployedSpec() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        mvc.perform(post("/api/v1/organizations/{org}/apis/import", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pets\",\"basePath\":\"/pets\",\"spec\":" + JSON.writeValueAsString(PETS) + "}"))
                .andExpect(status().isCreated());
        String servers = "/api/v1/organizations/{org}/mcp/servers";

        mvc.perform(post(servers, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"petstore\",\"type\":\"OPENAPI\",\"apiProxy\":\"missing\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(servers, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"petstore\",\"type\":\"OPENAPI\",\"apiProxy\":\"pets\",\"tools\":[\"getPet\",\"addPet\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gatewayProxy").value("mcp-petstore"))
                .andExpect(jsonPath("$.gatewayPath").value("/mcp/petstore"));

        mvc.perform(get(servers + "/petstore/tools", org).with(person(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("getPet", "addPet")))
                .andExpect(jsonPath("$[0].inputSchema.required[0]").value("petId"))
                .andExpect(jsonPath("$[0].http.path").value("/pets/{petId}"))
                .andExpect(jsonPath("$[0].annotations.readOnlyHint").value(true))
                .andExpect(jsonPath("$[1].inputSchema.properties.body.type").value("object"));

        // not deployed yet: not in the runtime feed, no gateway proxy
        String mcpFeed = "/internal/v1/runtime/organizations/{org}/environments/dev/mcp";
        mvc.perform(get(mcpFeed, org).with(gateway())).andExpect(jsonPath("$.servers").isEmpty());
        mvc.perform(post("/api/v1/organizations/{org}/environments/dev/apis/pets/revisions/1/deployments", org)
                        .with(person(alice)))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get(mcpFeed, org).with(gateway()))
                .andExpect(jsonPath("$.servers[0].name").value("petstore"))
                .andExpect(jsonPath("$.servers[0].proxyBasePath").value("/pets"))
                .andExpect(jsonPath("$.servers[0].tools[0].method").value("GET"))
                .andExpect(jsonPath("$.servers[0].tools[0].parameters[0].in").value("path"));
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev", org).with(gateway()))
                .andExpect(jsonPath("$.proxies[*].name", hasItem("mcp-petstore")))
                .andExpect(jsonPath("$.proxies[?(@.name == 'mcp-petstore')].basePath").value(hasItem("/mcp/petstore")));
    }

    @Test
    void remoteMcpServersGetTheirTokensFromManagement() throws Exception {
        String alice = uniqueName("alice");
        String org = createOrganization(alice);
        createEnvironment(org, "dev", person(alice));
        String servers = "/api/v1/organizations/{org}/mcp/servers";
        String issuer = "http://127.0.0.1:" + authServer.getAddress().getPort();

        mvc.perform(post(servers, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"docs","type":"REMOTE","remote":{"url":"https://mcp.example.com/mcp","auth":"BEARER","bearerToken":"static-token"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remote.secretSet").value(true))
                .andExpect(jsonPath("$.remote.bearerToken").doesNotExist());
        mvc.perform(post("/internal/v1/runtime/organizations/{org}/mcp-servers/docs/token", org).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("static-token"));

        mvc.perform(post(servers, org).with(person(alice)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"github","type":"REMOTE","tools":["search"],
                         "remote":{"url":"https://mcp.example.com/mcp","auth":"OAUTH_CLIENT_CREDENTIALS",
                                   "clientId":"portica","clientSecret":"s3cret","scopes":["repo"],"authorizationServer":"%s"}}"""
                        .formatted(issuer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remote.oauth.connected").value(false));
        String token = "/internal/v1/runtime/organizations/{org}/mcp-servers/github/token";
        mvc.perform(post(token, org).with(gateway()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("upstream-at-1"))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post(token, org).with(gateway())).andExpect(jsonPath("$.accessToken").value("upstream-at-1"));
        assertThat(tokenRequests.get()).as("cached until it expires").isEqualTo(1);
        assertThat(tokenForms.getFirst())
                .contains("grant_type=client_credentials", "scope=repo", "resource=")
                .contains("|Basic ");
        mvc.perform(post(token, org).with(person(alice))).andExpect(status().isForbidden());

        mvc.perform(get(servers + "/github", org).with(person(alice)))
                .andExpect(jsonPath("$.remote.oauth.connected").value(true))
                .andExpect(jsonPath("$.remote.oauth.scope").value("repo"))
                .andExpect(jsonPath("$.remote.oauth.tokenEndpoint").value(issuer + "/token"));
        mvc.perform(get("/internal/v1/runtime/organizations/{org}/environments/dev/mcp", org).with(gateway()))
                .andExpect(jsonPath("$.servers[?(@.name == 'github')].remoteToken").value(hasItem(true)))
                .andExpect(jsonPath("$.servers[?(@.name == 'github')].remoteUrl").value(hasItem("https://mcp.example.com/mcp")))
                .andExpect(jsonPath("$..clientSecret").isEmpty())
                .andExpect(jsonPath("$..accessToken").isEmpty());

        // signing in only applies to the authorization code flow
        mvc.perform(post(servers + "/github/oauth/authorize", org).with(person(alice)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void readersCanLookButNotChange() throws Exception {
        String alice = uniqueName("alice");
        String ann = uniqueName("ann");
        String org = createOrganization(alice);
        mvc.perform(post("/api/v1/organizations/{org}/members", org).with(person(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"roles\":[\"ANALYST\"]}".formatted(email(ann))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/organizations/{org}/ai/providers", org).with(person(ann))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/organizations/{org}/mcp/servers", org).with(person(ann))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/organizations/{org}/ai/providers", org).with(person(ann))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"OPENAI\",\"models\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/{org}/analytics/ai/summary", org).with(person(ann)))
                .andExpect(status().isServiceUnavailable());   // no ClickHouse in these tests
        mvc.perform(get("/api/v1/organizations/{org}/mcp/servers", org).with(person(uniqueName("outsider"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/organizations/{org}/ai/proxies", org).with(person(ann)))
                .andExpect(jsonPath("$[*].name", not(hasItem("x"))));
    }

    // ------------------------------------------------------------------ helpers

    static final String PETS = """
            {"openapi":"3.0.3","info":{"title":"Pets","version":"1"},
             "servers":[{"url":"https://pets.example.com/v1"}],
             "paths":{
               "/pets/{petId}":{"get":{"operationId":"getPet","summary":"Find a pet",
                  "parameters":[{"name":"petId","in":"path","required":true,"schema":{"type":"integer"}},
                                {"name":"verbose","in":"query","schema":{"type":"boolean"}}],
                  "responses":{"200":{"description":"ok"}}},
                 "delete":{"operationId":"deletePet","parameters":[{"name":"petId","in":"path","required":true,
                   "schema":{"type":"integer"}}],"responses":{"204":{"description":"gone"}}}},
               "/pets":{"post":{"operationId":"addPet",
                  "requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}},
                  "responses":{"201":{"description":"created"}}}}},
             "components":{"schemas":{"Pet":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}}""";

    private void createEnvironment(String org, String env, RequestPostProcessor as) throws Exception {
        mvc.perform(post("/api/v1/organizations/{org}/environments", org).with(as)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(env)))
                .andExpect(status().isCreated());
    }

    private ResultActions createProxy(String org, String name, String basePath,
                                                                         RequestPostProcessor as) throws Exception {
        return mvc.perform(post("/api/v1/organizations/{org}/apis", org).with(as)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"%s","revision":{"basePath":"%s","target":{"url":"https://backend.example.com"}}}
                        """.formatted(name, basePath)));
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

}
