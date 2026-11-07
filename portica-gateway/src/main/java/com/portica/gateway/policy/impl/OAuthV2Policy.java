package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.OAuthV2Config;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuthV2 VerifyAccessToken for JWT access tokens (RS256, e.g. Keycloak). Sets
 * {@code oauthv2.<name>.client_id}, {@code .scope}, {@code .subject}, {@code .claim.<claim>}.
 * Faults: {@code MissingAccessToken} (401), {@code InvalidAccessToken} (401),
 * {@code InsufficientScope} (403), with RFC 6750 {@code WWW-Authenticate} headers.
 */
public final class OAuthV2Policy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(OAuthV2Policy.class);

    private final String name;
    private final List<String> requiredScopes;
    private final ReactiveJwtDecoder decoder;

    public OAuthV2Policy(String name, OAuthV2Config config) {
        this.name = name;
        this.requiredScopes = config.requiredScopes();
        this.decoder = JwtSupport.jwksDecoder(config.jwksUri(), "RS256",
                JwtSupport.validator(config.clockSkewSeconds(), config.issuer(), config.audiences(), null, Map.of()));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String header = ctx.request().headers().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Mono.error(PolicyFault.of("MissingAccessToken", 401, "A bearer access token is required")
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }
        return decoder.decode(JwtSupport.bearer(header))
                .onErrorMap(JwtException.class, e -> {
                    log.debug("Access token rejected by policy {}: {}", name, e.getMessage());
                    return PolicyFault.of("InvalidAccessToken", 401, "The access token is not valid")
                            .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
                })
                .flatMap(jwt -> {
                    Set<String> granted = scopes(jwt);
                    if (!granted.containsAll(requiredScopes)) {
                        return Mono.error(PolicyFault.of("InsufficientScope", 403, "The access token lacks a required scope")
                                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\""
                                        + String.join(" ", requiredScopes) + "\""));
                    }
                    String p = MessageContext.prefix("oauthv2", name);
                    JwtSupport.publishClaims(ctx, p, jwt);
                    Object clientId = jwt.getClaims().getOrDefault("client_id", jwt.getClaims().get("azp"));
                    ctx.setVariable(p + "client_id", clientId == null ? null : String.valueOf(clientId));
                    ctx.setVariable(p + "scope", String.join(" ", granted));
                    return Mono.empty();
                });
    }

    static Set<String> scopes(Jwt jwt) {
        Set<String> scopes = new HashSet<>();
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof String s) {
            scopes.addAll(List.of(s.trim().split("\\s+")));
        }
        Object scp = jwt.getClaims().get("scp");
        if (scp instanceof Collection<?> values) {
            values.forEach(v -> scopes.add(String.valueOf(v)));
        } else if (scp instanceof String s) {
            scopes.addAll(List.of(s.trim().split("\\s+")));
        }
        scopes.remove("");
        return scopes;
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return placement == PolicyPhase.RESPONSE;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.OAUTH_V2;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new OAuthV2Policy(name, (OAuthV2Config) config);
        }
    }
}
