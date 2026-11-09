package com.portica.aigateway.provider;

import com.portica.aigateway.runtime.AiRuntime;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * One provider API. Requests and responses use the OpenAI chat completions format; implementations
 * translate where the provider speaks something else.
 */
public interface LlmProvider {

    record Usage(long promptTokens, long completionTokens) {

        public static final Usage NONE = new Usage(0, 0);

        public long total() {
            return promptTokens + completionTokens;
        }
    }

    /** {@code body} is a {@code chat.completion} object. */
    record Completion(ObjectNode body, Usage usage) {
    }

    /** Non-streaming call. */
    Mono<Completion> complete(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout);

    /**
     * Streaming call. The Mono completes once the provider accepted the call (so failures before the first
     * byte can still fall back to another target); the Flux then emits {@code chat.completion.chunk}
     * objects, ending with a chunk that carries {@code usage} and no choices.
     */
    Mono<Flux<ObjectNode>> stream(AiRuntime.Provider provider, String model, ObjectNode request, Duration timeout);
}
