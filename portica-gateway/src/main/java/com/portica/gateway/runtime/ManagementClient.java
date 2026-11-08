package com.portica.gateway.runtime;

import com.portica.gateway.trace.TraceSession;
import java.util.List;
import com.portica.gateway.config.PorticaGatewayProperties;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeIndex;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/** Reads the internal runtime API of portica-management. */
@Component
public class ManagementClient {

    private final WebClient webClient;
    private final ManagementTokenProvider tokens;
    private final Duration timeout;

    public ManagementClient(PorticaGatewayProperties properties, ManagementTokenProvider tokens) {
        this.webClient = WebClient.builder().baseUrl(properties.managementUrl()).build();
        this.tokens = tokens;
        this.timeout = properties.sync().timeout();
    }

    public Mono<RuntimeIndex> index() {
        return bearer().flatMap(auth -> webClient.get()
                .uri("/internal/v1/runtime/environments")
                .headers(h -> auth.ifPresent(token -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .retrieve()
                .bodyToMono(RuntimeIndex.class)
                .timeout(timeout));
    }

    /** The environment's snapshot, or empty if it no longer exists (deleted, or organization archived). */
    public Mono<Optional<RuntimeEnvironmentConfig>> snapshot(String organization, String environment) {
        return bearer().flatMap(auth -> webClient.get()
                .uri("/internal/v1/runtime/organizations/{org}/environments/{env}", organization, environment)
                .headers(h -> auth.ifPresent(token -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return response.releaseBody().thenReturn(Optional.<RuntimeEnvironmentConfig>empty());
                    }
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(RuntimeEnvironmentConfig.class).map(Optional::of);
                    }
                    return response.createError();
                })
                .timeout(timeout));
    }

    /** Active trace (debug) sessions for every environment. */
    public Mono<List<TraceSession>> traceSessions() {
        return bearer().flatMap(auth -> webClient.get()
                .uri("/internal/v1/runtime/trace-sessions")
                .headers(h -> auth.ifPresent(token -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .retrieve()
                .bodyToFlux(TraceSession.class)
                .collectList()
                .timeout(timeout));
    }

    private Mono<Optional<String>> bearer() {
        return tokens.token().map(Optional::of).defaultIfEmpty(Optional.empty());
    }
}
