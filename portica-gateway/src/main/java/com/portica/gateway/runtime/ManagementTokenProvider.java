package com.portica.gateway.runtime;

import com.portica.gateway.config.PorticaGatewayProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Obtains and caches an OAuth2 client-credentials access token for calling the management plane.
 * <p>
 * Implemented directly instead of with Spring Security's OAuth2 client because the gateway must
 * not get Spring Security's web auto-configuration: it would put a login wall in front of every
 * proxied API. Inbound API security is the job of policies (Phase 3).
 */
@Component
public class ManagementTokenProvider {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private final PorticaGatewayProperties.Auth auth;
    private final Duration timeout;
    private final WebClient webClient = WebClient.create();
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public ManagementTokenProvider(PorticaGatewayProperties properties) {
        this.auth = properties.auth();
        this.timeout = properties.sync().timeout();
    }

    /** A valid bearer token, or empty when no credentials are configured (e.g. local tests). */
    public Mono<String> token() {
        if (!auth.configured()) {
            return Mono.empty();
        }
        CachedToken current = cached.get();
        if (current != null && current.expiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN))) {
            return Mono.just(current.value());
        }
        return webClient.post()
                .uri(auth.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", auth.clientId())
                        .with("client_secret", auth.clientSecret() == null ? "" : auth.clientSecret()))
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(timeout)
                .map(this::cache);
    }

    private String cache(Map<String, Object> response) {
        Object accessToken = response.get("access_token");
        if (!(accessToken instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        cached.set(new CachedToken(value, Instant.now().plusSeconds(expiresIn)));
        return value;
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
