package com.portica.aigateway.provider;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicTranslationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void mapsOpenAiRequestsToMessages() {
        ObjectNode request = (ObjectNode) json.readTree("""
                {"model":"x","temperature":0.2,"stop":"END","messages":[
                  {"role":"system","content":"Rules"},
                  {"role":"assistant","content":"Earlier answer"},
                  {"role":"user","content":[{"type":"text","text":"Look"},
                     {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}""");
        ObjectNode out = AnthropicProvider.toAnthropic(request, "claude-haiku", false);
        assertThat(out.path("model").asString()).isEqualTo("claude-haiku");
        assertThat(out.path("system").asString()).isEqualTo("Rules");
        assertThat(out.path("max_tokens").asLong()).isEqualTo(AnthropicProvider.DEFAULT_MAX_TOKENS);
        assertThat(out.path("stop_sequences").get(0).asString()).isEqualTo("END");
        JsonNode messages = out.path("messages");
        // Anthropic needs a user turn first
        assertThat(messages.get(0).path("role").asString()).isEqualTo("user");
        assertThat(messages.get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(messages.get(2).path("content").get(1).path("source").path("media_type").asString()).isEqualTo("image/png");
    }

    @Test
    void translatesStreamEvents() {
        AnthropicProvider.StreamState state = new AnthropicProvider.StreamState("claude-haiku");
        List<ObjectNode> start = state.translate(json.readTree(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":7}}}"));
        assertThat(start.getFirst().path("choices").get(0).path("delta").path("role").asString()).isEqualTo("assistant");
        assertThat(state.translate(json.readTree("{\"type\":\"ping\"}"))).isEmpty();
        state.translate(json.readTree("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":3}}"));
        ObjectNode last = state.translate(json.readTree("{\"type\":\"message_stop\"}")).getFirst();
        assertThat(last.path("usage").path("total_tokens").asLong()).isEqualTo(10);
        assertThat(AnthropicProvider.finishReason("max_tokens")).isEqualTo("length");
    }
}
