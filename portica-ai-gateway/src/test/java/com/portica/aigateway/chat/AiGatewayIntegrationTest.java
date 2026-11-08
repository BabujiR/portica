package com.portica.aigateway.chat;

import com.portica.aigateway.MockLlmServer;
import com.portica.aigateway.quota.MemoryTokenQuotaStore;
import com.portica.aigateway.quota.TokenQuotaStore;
import com.portica.aigateway.runtime.AiConfigFeed;
import com.portica.aigateway.runtime.AiRuntime;
import com.portica.common.analytics.AnalyticsTopics;
import com.portica.common.analytics.EventSink;
import com.portica.common.http.PorticaHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI gateway against a mock LLM provider that speaks both the OpenAI and the Anthropic API:
 * routing and fallback, translation, streaming, token quota, cost, PII policies and analytics events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.ai.sync.enabled=false",
        "portica.ai.quota-store=memory",
        "portica.ai.analytics.enabled=false",
        "portica.ai.internal-token=test-internal"})
class AiGatewayIntegrationTest {

    record Published(String topic, Map<String, Object> event) {
    }

    static final List<Published> published = new CopyOnWriteArrayList<>();
    static MockLlmServer llm;

    @TestConfiguration
    static class Capture {
        @Bean
        @Primary
        EventSink capturingSink() {
            return (topic, key, event) -> published.add(new Published(topic, event));
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    AiConfigFeed feed;

    @Autowired
    TokenQuotaStore quotas;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    static void startLlm() throws Exception {
        llm = new MockLlmServer();
    }

    @AfterAll
    static void stopLlm() {
        llm.close();
    }

    @BeforeEach
    void configure() {
        published.clear();
        llm.requests.clear();
        ((MemoryTokenQuotaStore) quotas).clear();
        List<AiRuntime.Model> gpt = List.of(new AiRuntime.Model("gpt-4o-mini", new BigDecimal("0.15"), new BigDecimal("0.60")),
                new AiRuntime.Model("gpt-4o", new BigDecimal("2.50"), new BigDecimal("10")));
        List<AiRuntime.Model> claude = List.of(new AiRuntime.Model("claude-haiku", new BigDecimal("1"), new BigDecimal("5")));
        List<AiRuntime.Provider> providers = List.of(
                new AiRuntime.Provider("openai", "OPENAI", llm.url("openai"), "sk-test-openai", true, gpt),
                new AiRuntime.Provider("busy", "OPENAI", llm.url("busy"), "sk-x", true, gpt),
                new AiRuntime.Provider("anthropic", "ANTHROPIC", llm.url("anthropic"), "sk-test-anthropic", true, claude));
        List<AiRuntime.Route> routes = List.of(
                new AiRuntime.Route("fast", List.of(new AiRuntime.Target("openai", "gpt-4o-mini"))),
                new AiRuntime.Route("smart", List.of(new AiRuntime.Target("busy", "gpt-4o"),
                        new AiRuntime.Target("anthropic", "claude-haiku"))));
        AiRuntime.Pii off = new AiRuntime.Pii("OFF", List.of(), false);
        List<AiRuntime.Proxy> proxies = List.of(
                new AiRuntime.Proxy("assistant", routes, "fast", true, null, off, 30, null),
                new AiRuntime.Proxy("private", routes, "fast", false, null,
                        new AiRuntime.Pii("REDACT", List.of(), true), 30, null),
                new AiRuntime.Proxy("strict", routes, "fast", false, null,
                        new AiRuntime.Pii("BLOCK", List.of("EMAIL", "CREDIT_CARD"), false), 30, null),
                new AiRuntime.Proxy("metered", routes, "fast", false, new AiRuntime.TokenQuota(25, 1, "day"), off, 30, 256));
        feed.put(new AiRuntime.Config("acme", "dev", 1, providers, proxies));
    }

    @Test
    void routesToTheProviderAndReportsCost() throws Exception {
        HttpResponse<String> response = chat("assistant", "shop", """
                {"model":"fast","messages":[{"role":"user","content":"Hello"}]}""");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Portica-AI-Provider")).hasValue("openai");
        assertThat(response.headers().firstValue("X-Portica-AI-Cost-USD")).hasValue("0.0000066");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("choices").get(0).path("message").path("content").asString()).startsWith("Hello from OpenAI");
        assertThat(llm.requestsTo("openai").getFirst().body().path("model").asString()).isEqualTo("gpt-4o-mini");

        Map<String, Object> event = awaitEvent();
        assertThat(event).containsEntry("organization", "acme").containsEntry("ai_proxy", "assistant")
                .containsEntry("model_alias", "fast").containsEntry("provider", "openai").containsEntry("model", "gpt-4o-mini")
                .containsEntry("app", "shop").containsEntry("status", 200).containsEntry("total_tokens", 20L)
                .containsEntry("attempts", 1).containsEntry("streamed", 0);
        assertThat((Double) event.get("cost_usd")).isEqualTo(0.0000066);
    }

    @Test
    void fallsBackToAnthropicAndTranslatesTheResponse() throws Exception {
        HttpResponse<String> response = chat("assistant", "shop", """
                {"model":"smart","max_tokens":100,"messages":[
                  {"role":"system","content":"Be brief."},
                  {"role":"user","content":"Hi"},
                  {"role":"user","content":[{"type":"text","text":"Still there?"}]}]}""");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Portica-AI-Provider")).hasValue("anthropic");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("object").asString()).isEqualTo("chat.completion");
        assertThat(body.path("choices").get(0).path("message").path("content").asString()).isEqualTo("Hi from Claude");
        assertThat(body.path("choices").get(0).path("finish_reason").asString()).isEqualTo("stop");
        assertThat(body.path("usage").path("total_tokens").asLong()).isEqualTo(25);

        assertThat(llm.requestsTo("busy")).hasSize(1);
        JsonNode sent = llm.requestsTo("anthropic").getFirst().body();
        assertThat(sent.path("system").asString()).isEqualTo("Be brief.");
        assertThat(sent.path("max_tokens").asLong()).isEqualTo(100);
        assertThat(sent.path("messages")).hasSize(1);   // the two user turns are merged
        assertThat(sent.path("messages").get(0).path("content")).hasSize(2);
        assertThat(awaitEvent()).containsEntry("attempts", 2).containsEntry("provider", "anthropic");
    }

    @Test
    void streamsAnthropicAsOpenAiChunksAndCountsTokens() throws Exception {
        HttpResponse<String> response = chat("assistant", "shop", """
                {"model":"anthropic/claude-haiku","stream":true,"messages":[{"role":"user","content":"Hi"}]}""");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<String> data = response.body().lines().filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5).strip()).toList();
        assertThat(data.getLast()).isEqualTo("[DONE]");
        StringBuilder text = new StringBuilder();
        for (String d : data.subList(0, data.size() - 1)) {
            JsonNode chunk = json.readTree(d);
            assertThat(chunk.path("object").asString()).isEqualTo("chat.completion.chunk");
            assertThat(chunk.has("usage")).as("usage chunk only when asked for").isFalse();
            text.append(chunk.path("choices").get(0).path("delta").path("content").asString(""));
        }
        assertThat(text.toString()).isEqualTo("Hi there");
        Map<String, Object> event = awaitEvent();
        assertThat(event).containsEntry("streamed", 1).containsEntry("prompt_tokens", 20L)
                .containsEntry("completion_tokens", 5L).containsEntry("status", 200);
    }

    @Test
    void streamsOpenAiWithUsageWhenAsked() throws Exception {
        HttpResponse<String> response = chat("assistant", "shop", """
                {"model":"fast","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Hi"}]}""");
        assertThat(response.body()).contains("\"Hello\"").contains("\"total_tokens\":20").contains("[DONE]");
        assertThat(llm.requestsTo("openai").getFirst().body().path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void redactsPersonalDataInPromptsAndResponses() throws Exception {
        HttpResponse<String> response = chat("private", "shop", """
                {"messages":[{"role":"user","content":"I'm bob@example.com, card 4111 1111 1111 1111, call +1 415-555-0100"}]}""");
        assertThat(response.statusCode()).isEqualTo(200);
        String sent = llm.requestsTo("openai").getFirst().body().path("messages").get(0).path("content").asString();
        assertThat(sent).isEqualTo("I'm [EMAIL], card [CREDIT_CARD], call [PHONE]");
        assertThat(json.readTree(response.body()).path("choices").get(0).path("message").path("content").asString())
                .isEqualTo("Hello from OpenAI. Mail me at [EMAIL]");
        assertThat(awaitEvent()).containsEntry("pii_redactions", 4);
    }

    @Test
    void blocksPromptsWithPersonalData() throws Exception {
        HttpResponse<String> response = chat("strict", "shop", """
                {"messages":[{"role":"user","content":"My email is bob@example.com"}]}""");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo("pii_detected");
        assertThat(llm.requests).isEmpty();
        assertThat(chat("strict", "shop", """
                {"messages":[{"role":"user","content":"Call 415-555-0100"}]}""").statusCode())
                .as("phone numbers aren't in this proxy's types").isEqualTo(200);
    }

    @Test
    void enforcesTheTokenQuotaPerApp() throws Exception {
        String body = """
                {"messages":[{"role":"user","content":"Hi"}],"max_tokens":5000}""";
        HttpResponse<String> first = chat("metered", "shop", body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-Portica-AI-Tokens-Remaining")).hasValue("5");
        assertThat(llm.requestsTo("openai").getFirst().body().path("max_tokens").asLong()).as("capped").isEqualTo(256);
        assertThat(chat("metered", "shop", body).statusCode()).as("5 tokens left: still allowed").isEqualTo(200);

        HttpResponse<String> refused = chat("metered", "shop", body);
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(json.readTree(refused.body()).path("error").path("code").asString()).isEqualTo("token_quota_exceeded");
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(chat("metered", "other-app", body).statusCode()).as("quotas are per app").isEqualTo(200);
    }

    @Test
    void rejectsUnknownModelsAndListsTheAvailableOnes() throws Exception {
        HttpResponse<String> response = chat("assistant", "shop", """
                {"model":"gpt-5","messages":[{"role":"user","content":"Hi"}]}""");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).path("error").path("code").asString()).isEqualTo("model_not_found");

        HttpResponse<String> models = send(HttpRequest.newBuilder(uri("assistant", "/models"))
                .header(PorticaHeaders.INTERNAL_TOKEN, "test-internal").GET());
        assertThat(models.body()).contains("\"fast\"", "\"smart\"", "\"anthropic/claude-haiku\"");
    }

    @Test
    void onlyAcceptsCallsThroughTheGateway() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("assistant", "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}")));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(llm.requests).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private URI uri(String proxy, String suffix) {
        return URI.create("http://localhost:" + port + "/internal/ai/acme/dev/" + proxy + "/v1" + suffix);
    }

    private HttpResponse<String> chat(String proxy, String app, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(proxy, "/chat/completions"))
                .header("Content-Type", "application/json")
                .header(PorticaHeaders.INTERNAL_TOKEN, "test-internal")
                .header(PorticaHeaders.APP, app)
                .header(PorticaHeaders.DEVELOPER, "dev@example.com")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> awaitEvent() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            List<Published> ai = published.stream().filter(p -> p.topic().equals(AnalyticsTopics.AI_REQUESTS)).toList();
            if (!ai.isEmpty()) {
                return ai.getLast().event();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no AI event published");
    }
}
