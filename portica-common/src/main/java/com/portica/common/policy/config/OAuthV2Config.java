package com.portica.common.policy.config;

import java.util.List;

/**
 * OAuthV2 (operation VerifyAccessToken): requires a valid OAuth 2.0 bearer access token, and optionally
 * required scopes. Two token sources:
 * <ul>
 *   <li>without {@code jwksUri}: tokens issued by the Portica gateway's client-credentials endpoint
 *       ({@code POST /{org}/{env}/oauth2/token}); the calling app must hold an API product that
 *       includes this proxy;</li>
 *   <li>with {@code jwksUri}: JWT access tokens from an external authorization server (e.g. Keycloak).</li>
 * </ul>
 *
 * @param operation        only {@code VerifyAccessToken} is supported for now
 * @param jwksUri          an external authorization server's JWK Set URL; omit for Portica-issued tokens
 * @param issuer           expected {@code iss} claim (recommended)
 * @param audiences        accepted {@code aud} values (any match)
 * @param requiredScopes   scopes that must all be granted
 * @param clockSkewSeconds tolerance for exp/nbf (default 60)
 */
public record OAuthV2Config(String operation, String jwksUri, String issuer, List<String> audiences,
                            List<String> requiredScopes, Long clockSkewSeconds) implements PolicyConfig {

    public OAuthV2Config {
        operation = operation == null ? "VerifyAccessToken" : operation;
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
        clockSkewSeconds = clockSkewSeconds == null ? 60L : clockSkewSeconds;
    }

    /** True when tokens come from Portica's own token endpoint. */
    public boolean porticaTokens() {
        return jwksUri == null || jwksUri.isBlank();
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .require(operation.equals("VerifyAccessToken"), "'operation' must be VerifyAccessToken")
                .httpUrl(jwksUri, "jwksUri")
                .require(jwksUri != null || (issuer == null && audiences.isEmpty()),
                        "'issuer' and 'audiences' apply only to external tokens (set 'jwksUri')")
                .require(jwksUri == null || !jwksUri.contains("{"), "'jwksUri' cannot use variables")
                .range(clockSkewSeconds, 0, 3600, "clockSkewSeconds")
                .list();
    }

}
