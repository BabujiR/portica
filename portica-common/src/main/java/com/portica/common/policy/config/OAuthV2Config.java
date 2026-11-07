package com.portica.common.policy.config;

import java.util.List;

/**
 * OAuthV2 (operation VerifyAccessToken): requires a valid OAuth 2.0 bearer access token in JWT format
 * from a trusted authorization server (e.g. Keycloak), and optionally required scopes.
 * Portica-issued tokens (client credentials grant) arrive in Phase 4.
 *
 * @param operation        only {@code VerifyAccessToken} is supported for now
 * @param jwksUri          the authorization server's JWK Set URL (required)
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

    @Override
    public List<String> validate() {
        return new Problems()
                .require(operation.equals("VerifyAccessToken"), "'operation' must be VerifyAccessToken")
                .notBlank(jwksUri, "jwksUri")
                .httpUrl(jwksUri, "jwksUri")
                .require(jwksUri == null || !jwksUri.contains("{"), "'jwksUri' cannot use variables")
                .range(clockSkewSeconds, 0, 3600, "clockSkewSeconds")
                .list();
    }

}
