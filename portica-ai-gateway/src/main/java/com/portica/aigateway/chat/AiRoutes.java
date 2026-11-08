package com.portica.aigateway.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Internal paths the gateway's generated {@code ai-<name>} proxies forward to. Clients call
 * {@code {gateway}/{org}/{env}/ai/{name}/v1/chat/completions}; the gateway verifies their credential and
 * rewrites the path to {@code /internal/ai/{org}/{env}/{name}/v1/chat/completions}.
 */
@Configuration
public class AiRoutes {

    static final String BASE = "/internal/ai/{org}/{env}/{proxy}/v1";

    @Bean
    RouterFunction<ServerResponse> aiEndpoints(ChatHandler chat) {
        return route(POST(BASE + "/chat/completions"), chat::chat)
                .andRoute(GET(BASE + "/models"), chat::models);
    }
}
