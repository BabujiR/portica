package com.portica.aigateway.provider;

import com.portica.aigateway.runtime.AiRuntime;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic's Messages API behind the OpenAI format: system messages become {@code system}, consecutive
 * messages of one role are merged (Anthropic requires alternating turns), text and base64 images are
 * carried over, and responses and stream events are translated back. Tool calling is not translated.
 */
@Component
public class AnthropicProvider implements LlmProvider {

    static final String VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 4096;

    private final WebClient web;

    public AnthropicProvider(WebClient.Builder builder) {
        this.web = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }

    @Override
    public Mono<Completion> complete(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout) {
        ObjectNode body = toAnthropic(request, model, false);
        return post(provider, body, MediaType.APPLICATION_JSON)
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(text -> {
                    JsonNode node = Http.parse(text);
                    if (node == null || !node.isObject() || !node.has("content")) {
                        throw ProviderException.of(502, "The provider's response isn't a message");
                    }
                    Usage usage = new Usage(node.path("usage").path("input_tokens").asLong(0),
                            node.path("usage").path("output_tokens").asLong(0));
                    return new Completion(toOpenAi(node, usage), usage);
                });
    }

    @Override
    public Mono<Flux<ObjectNode>> stream(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout) {
        ObjectNode body = toAnthropic(request, model, true);
        return post(provider, body, MediaType.TEXT_EVENT_STREAM)
                .toEntityFlux(String.class)
                .timeout(timeout)
                .map(entity -> {
                    Flux<String> data = entity.getBody() == null ? Flux.empty() : entity.getBody();
                    StreamState state = new StreamState(model);
                    return data.timeout(timeout)
                            .filter(line -> !line.isBlank())
                            .concatMapIterable(line -> state.translate(Http.parse(line)));
                });
    }

    private WebClient.ResponseSpec post(AiRuntime.Provider provider, ObjectNode body, MediaType accept) {
        return web.post().uri(provider.baseUrl() + "/messages")
                .headers(h -> {
                    if (provider.apiKey() != null) {
                        h.set("x-api-key", provider.apiKey());
                    }
                    h.set("anthropic-version", VERSION);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(accept)
                .bodyValue(Http.JSON.writeValueAsString(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, Http::error);
    }

    // ------------------------------------------------------------------ request translation

    static ObjectNode toAnthropic(ObjectNode openAi, String model, boolean stream) {
        ObjectNode out = Http.JSON.createObjectNode();
        out.put("model", model);
        List<String> system = new ArrayList<>();
        ArrayNode messages = out.putArray("messages");
        String lastRole = null;
        ArrayNode lastContent = null;
        for (JsonNode m : openAi.path("messages")) {
            String role = m.path("role").asString("user");
            if (role.equals("system") || role.equals("developer")) {
                system.add(text(m.path("content")));
                continue;
            }
            String anthropicRole = role.equals("assistant") ? "assistant" : "user";
            ArrayNode content = Http.JSON.createArrayNode();
            if (role.equals("tool")) {
                content.addObject().put("type", "text").put("text", "Tool result: " + text(m.path("content")));
            } else {
                blocks(m.path("content"), content);
            }
            if (content.isEmpty()) {
                continue;
            }
            if (anthropicRole.equals(lastRole)) {
                lastContent.addAll(content);
            } else {
                ObjectNode msg = messages.addObject();
                msg.put("role", anthropicRole);
                lastContent = msg.putArray("content");
                lastContent.addAll(content);
                lastRole = anthropicRole;
            }
        }
        if (!messages.isEmpty() && messages.get(0).path("role").asString().equals("assistant")) {
            ObjectNode first = Http.JSON.createObjectNode();
            first.put("role", "user");
            first.putArray("content").addObject().put("type", "text").put("text", "(continue)");
            messages.insert(0, first);
        }
        if (!system.isEmpty()) {
            out.put("system", String.join("\n\n", system));
        }
        long maxTokens = openAi.path("max_completion_tokens").asLong(openAi.path("max_tokens").asLong(DEFAULT_MAX_TOKENS));
        out.put("max_tokens", maxTokens);
        copyNumber(openAi, out, "temperature");
        copyNumber(openAi, out, "top_p");
        JsonNode stop = openAi.path("stop");
        if (stop.isString()) {
            out.putArray("stop_sequences").add(stop.asString());
        } else if (stop.isArray()) {
            out.set("stop_sequences", stop.deepCopy());
        }
        if (openAi.path("user").isString()) {
            out.putObject("metadata").put("user_id", openAi.path("user").asString());
        }
        if (stream) {
            out.put("stream", true);
        }
        return out;
    }

    private static void blocks(JsonNode content, ArrayNode out) {
        if (content.isString()) {
            if (!content.asString().isEmpty()) {
                out.addObject().put("type", "text").put("text", content.asString());
            }
            return;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asString();
            if (type.equals("text")) {
                out.addObject().put("type", "text").put("text", part.path("text").asString());
            } else if (type.equals("image_url")) {
                String url = part.path("image_url").path("url").asString();
                int comma = url.indexOf(',');
                if (url.startsWith("data:") && url.contains(";base64,") && comma > 0) {
                    ObjectNode image = out.addObject();
                    image.put("type", "image");
                    ObjectNode source = image.putObject("source");
                    source.put("type", "base64");
                    source.put("media_type", url.substring(5, url.indexOf(';')));
                    source.put("data", url.substring(comma + 1));
                } else if (url.startsWith("http")) {
                    ObjectNode image = out.addObject();
                    image.put("type", "image");
                    image.putObject("source").put("type", "url").put("url", url);
                }
            }
        }
    }

    static String text(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.path("type").asString().equals("text")) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(part.path("text").asString());
            }
        }
        return sb.toString();
    }

    private static void copyNumber(JsonNode from, ObjectNode to, String field) {
        if (from.path(field).isNumber()) {
            to.set(field, from.path(field).deepCopy());
        }
    }

    // ------------------------------------------------------------------ response translation

    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop";
        };
    }

    static ObjectNode toOpenAi(JsonNode message, Usage usage) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : message.path("content")) {
            if (block.path("type").asString().equals("text")) {
                text.append(block.path("text").asString());
            }
        }
        ObjectNode out = Http.JSON.createObjectNode();
        out.put("id", "chatcmpl-" + message.path("id").asString("anthropic"));
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", message.path("model").asString());
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode msg = choice.putObject("message");
        msg.put("role", "assistant");
        msg.put("content", text.toString());
        choice.put("finish_reason", finishReason(message.path("stop_reason").asString(null)));
        ObjectNode u = out.putObject("usage");
        u.put("prompt_tokens", usage.promptTokens());
        u.put("completion_tokens", usage.completionTokens());
        u.put("total_tokens", usage.total());
        return out;
    }

    /** Turns Anthropic stream events into OpenAI chunks. */
    static final class StreamState {
        private final String model;
        private final AtomicReference<String> id = new AtomicReference<>("chatcmpl-anthropic");
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final long created = Instant.now().getEpochSecond();

        StreamState(String model) {
            this.model = model;
        }

        List<ObjectNode> translate(JsonNode event) {
            if (event == null) {
                return List.of();
            }
            switch (event.path("type").asString()) {
                case "message_start" -> {
                    JsonNode message = event.path("message");
                    id.set("chatcmpl-" + message.path("id").asString("anthropic"));
                    input.set(message.path("usage").path("input_tokens").asLong(0));
                    output.set(message.path("usage").path("output_tokens").asLong(0));
                    ObjectNode chunk = chunk();
                    chunk.withArray("choices").addObject().put("index", 0)
                            .putNull("finish_reason")
                            .putObject("delta").put("role", "assistant").put("content", "");
                    return List.of(chunk);
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    if (!delta.path("type").asString().equals("text_delta")) {
                        return List.of();
                    }
                    ObjectNode chunk = chunk();
                    ObjectNode choice = chunk.withArray("choices").addObject();
                    choice.put("index", 0);
                    choice.putObject("delta").put("content", delta.path("text").asString());
                    choice.putNull("finish_reason");
                    return List.of(chunk);
                }
                case "message_delta" -> {
                    if (event.path("usage").has("output_tokens")) {
                        output.set(event.path("usage").path("output_tokens").asLong(0));
                    }
                    if (event.path("usage").has("input_tokens")) {
                        input.set(event.path("usage").path("input_tokens").asLong(0));
                    }
                    ObjectNode chunk = chunk();
                    ObjectNode choice = chunk.withArray("choices").addObject();
                    choice.put("index", 0);
                    choice.putObject("delta");
                    choice.put("finish_reason", finishReason(event.path("delta").path("stop_reason").asString(null)));
                    return List.of(chunk);
                }
                case "message_stop" -> {
                    ObjectNode chunk = chunk();
                    chunk.putArray("choices");
                    ObjectNode u = chunk.putObject("usage");
                    u.put("prompt_tokens", input.get());
                    u.put("completion_tokens", output.get());
                    u.put("total_tokens", input.get() + output.get());
                    return List.of(chunk);
                }
                case "error" -> throw ProviderException.of(502, event.path("error").path("message").asString("Provider error"));
                default -> {
                    return List.of();
                }
            }
        }

        private ObjectNode chunk() {
            ObjectNode chunk = Http.JSON.createObjectNode();
            chunk.put("id", id.get());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.putArray("choices");
            return chunk;
        }
    }
}
