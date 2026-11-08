package com.portica.aigateway.provider;

import com.portica.aigateway.runtime.AiRuntime;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * OpenAI's chat completions API, and any server that speaks it (Azure OpenAI deployments behind a
 * compatible endpoint, vLLM, Ollama, LiteLLM ...). Requests pass through with the model replaced;
 * streams always ask for usage so tokens can be counted.
 */
@Component
public class OpenAiProvider implements LlmProvider {

    private final WebClient web;

    public OpenAiProvider(WebClient.Builder builder) {
        this.web = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }

    @Override
    public Mono<Completion> complete(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout) {
        ObjectNode body = request.deepCopy();
        body.put("model", model);
        body.remove("stream");
        body.remove("stream_options");
        return web.post().uri(provider.baseUrl() + "/chat/completions")
                .headers(h -> {
                    if (provider.apiKey() != null) {
                        h.setBearerAuth(provider.apiKey());
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Http.JSON.writeValueAsString(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, Http::error)
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(text -> {
                    JsonNode node = Http.parse(text);
                    if (node == null || !node.isObject() || !node.has("choices")) {
                        throw ProviderException.of(502, "The provider's response isn't a chat completion");
                    }
                    return new Completion((ObjectNode) node, usage(node.path("usage")));
                });
    }

    @Override
    public Mono<Flux<ObjectNode>> stream(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout) {
        ObjectNode body = request.deepCopy();
        body.put("model", model);
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        return web.post().uri(provider.baseUrl() + "/chat/completions")
                .headers(h -> {
                    if (provider.apiKey() != null) {
                        h.setBearerAuth(provider.apiKey());
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Http.JSON.writeValueAsString(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, Http::error)
                .toEntityFlux(String.class)
                .timeout(timeout)
                .map(entity -> {
                    Flux<String> data = entity.getBody() == null ? Flux.empty() : entity.getBody();
                    return data.timeout(timeout)
                            .filter(line -> !line.isBlank() && !line.strip().equals("[DONE]"))
                            .map(line -> {
                                JsonNode node = Http.parse(line);
                                if (node == null || !node.isObject()) {
                                    throw ProviderException.of(502, "The provider sent an unreadable stream event");
                                }
                                if (node.has("error")) {
                                    throw ProviderException.of(502, node.path("error").path("message").asString("Provider error"));
                                }
                                return (ObjectNode) node;
                            });
                });
    }

    static Usage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Usage.NONE;
        }
        return new Usage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }
}
