package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.security.Hashing;
import com.portica.gateway.oauth.AccessTokenStore;
import com.portica.gateway.policy.credentials.ApiProductGrant;
import com.portica.gateway.policy.credentials.AppCredential;
import com.portica.gateway.policy.credentials.AppVariables;
import com.portica.gateway.policy.credentials.CredentialRegistry;
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

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuthV2 VerifyAccessToken, for two kinds of bearer tokens:
 * <ul>
 *   <li><b>Portica tokens</b> (no {@code jwksUri}): opaque tokens from the gateway's client-credentials
 *       endpoint. The app's credential is looked up again on every call, so revoking the app, key,
 *       product or developer stops its tokens at the next sync. The app must hold an API product that
 *       includes this proxy. Sets the app variables of {@link AppVariables} under {@code oauthv2.<name>.}
 *       and {@code .scope}.</li>
 *   <li><b>External JWTs</b> ({@code jwksUri} set, RS256, e.g. Keycloak). Sets
 *       {@code oauthv2.<name>.client_id}, {@code .scope}, {@code .subject}, {@code .claim.<claim>}.</li>
 * </ul>
 * Faults: {@code MissingAccessToken} (401), {@code InvalidAccessToken} (401), {@code InsufficientScope} (403),
 * {@code NoApiProductMatchFound} (403, Portica tokens) and {@code TokenStoreUnavailable} (503), with RFC 6750
 * {@code WWW-Authenticate} headers.
 */
public final class OAuthV2Policy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(OAuthV2Policy.class);

    private final String name;
    private final List<String> requiredScopes;
    private final ReactiveJwtDecoder decoder;
    private final AccessTokenStore tokens;
    private final CredentialRegistry credentials;
    private final Clock clock;

    /** External JWT mode ({@code jwksUri} required). */
    public OAuthV2Policy(String name, OAuthV2Config config) {
        this(name, config, null, null, Clock.systemUTC());
    }

    public OAuthV2Policy(String name, OAuthV2Config config, AccessTokenStore tokens, CredentialRegistry credentials,
                         Clock clock) {
        this.name = name;
        this.requiredScopes = config.requiredScopes();
        this.clock = clock;
        if (config.porticaTokens()) {
            this.decoder = null;
            this.tokens = java.util.Objects.requireNonNull(tokens, "tokens");
            this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
        } else {
            this.decoder = JwtSupport.jwksDecoder(config.jwksUri(), "RS256",
                    JwtSupport.validator(config.clockSkewSeconds(), config.issuer(), config.audiences(), null, Map.of()));
            this.tokens = null;
            this.credentials = null;
        }
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
        if (decoder == null) {
            return verifyPorticaToken(ctx, JwtSupport.bearer(header));
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

    private Mono<Void> verifyPorticaToken(MessageContext ctx, String token) {
        MessageContext.ProxyInfo proxy = ctx.proxy();
        return tokens.find(Hashing.sha256Hex(token))
                .onErrorMap(e -> {
                    log.warn("Access token store unavailable for policy {}: {}", name, e.toString());
                    return PolicyFault.of("TokenStoreUnavailable", 503, "Access tokens can't be checked right now");
                })
                .switchIfEmpty(Mono.error(OAuthV2Policy::invalidToken))
                .flatMap(record -> {
                    Instant now = clock.instant();
                    if (!record.organization().equals(proxy.organization()) || !record.environment().equals(proxy.environment())
                            || !record.expiresAt().isAfter(now)) {
                        return Mono.error(invalidToken());
                    }
                    AppCredential app = credentials.index(proxy.organization(), proxy.environment())
                            .findByKeyHash(record.keyHash(), record.clientId()).orElse(null);
                    if (app == null || app.expired(now)) {
                        return Mono.error(invalidToken()); // credential revoked, expired or removed since issuance
                    }
                    ApiProductGrant product = app.productFor(proxy.proxy(), ctx.request().pathSuffix()).orElse(null);
                    if (product == null) {
                        return Mono.error(PolicyFault.of("NoApiProductMatchFound", 403,
                                        "The access token is not authorized for this API resource")
                                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""));
                    }
                    if (!record.scopes().containsAll(requiredScopes)) {
                        return Mono.error(insufficientScope());
                    }
                    String p = MessageContext.prefix("oauthv2", name);
                    AppVariables.publish(ctx, p, app, product);
                    ctx.setVariable(p + "scope", String.join(" ", record.scopes()));
                    ctx.setVariable(p + "expires_at", record.expiresAt().toEpochMilli());
                    return Mono.empty();
                });
    }

    private static PolicyFault invalidToken() {
        return PolicyFault.of("InvalidAccessToken", 401, "The access token is not valid")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }

    private PolicyFault insufficientScope() {
        return PolicyFault.of("InsufficientScope", 403, "The access token lacks a required scope")
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\""
                        + String.join(" ", requiredScopes) + "\"");
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

        private final AccessTokenStore tokens;
        private final CredentialRegistry credentials;

        public Factory(AccessTokenStore tokens, CredentialRegistry credentials) {
            this.tokens = tokens;
            this.credentials = credentials;
        }

        @Override
        public PolicyType type() {
            return PolicyType.OAUTH_V2;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new OAuthV2Policy(name, (OAuthV2Config) config, tokens, credentials, Clock.systemUTC());
        }
    }
}
