package com.portica.aigateway.provider;

import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared bits of the provider clients. */
final class Http {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private Http() {
    }

    /** Turns a provider error response into a {@link ProviderException} with the provider's message. */
    static Mono<Throwable> error(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
            String message = "HTTP " + status;
            try {
                JsonNode node = JSON.readTree(body);
                JsonNode error = node.path("error");
                if (error.isObject() && error.path("message").isString()) {
                    message = error.path("message").asString();
                } else if (error.isString()) {
                    message = error.asString();
                } else if (node.path("message").isString()) {
                    message = node.path("message").asString();
                }
            } catch (RuntimeException ignored) {
                // not JSON
            }
            return ProviderException.of(status, message.length() > 500 ? message.substring(0, 500) : message);
        });
    }

    static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
