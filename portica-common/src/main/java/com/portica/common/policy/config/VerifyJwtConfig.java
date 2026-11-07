package com.portica.common.policy.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VerifyJWT: verifies a signed JWT's signature and claims.
 *
 * @param source           template giving the token (default {@code {request.header.authorization}};
 *                         a leading {@code Bearer } is stripped)
 * @param algorithm        RS256/384/512, PS256/384/512, ES256/384/512 (with {@code jwksUri})
 *                         or HS256/384/512 (with {@code secret})
 * @param jwksUri          JWK Set URL for asymmetric algorithms
 * @param secret           shared secret for HMAC algorithms (a template; keep real secrets out of
 *                         bundles once Key Value Maps arrive in Phase 4)
 * @param issuer           expected {@code iss}
 * @param audiences        accepted {@code aud} values (any match)
 * @param subject          expected {@code sub}
 * @param claims           other required claim values
 * @param clockSkewSeconds tolerance for exp/nbf (default 60)
 */
public record VerifyJwtConfig(String source, String algorithm, String jwksUri, String secret, String issuer,
                              List<String> audiences, String subject, Map<String, String> claims,
                              Long clockSkewSeconds) implements PolicyConfig {

    private static final Set<String> ASYMMETRIC = Set.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512",
            "ES256", "ES384", "ES512");
    private static final Set<String> HMAC = Set.of("HS256", "HS384", "HS512");

    public VerifyJwtConfig {
        source = source == null || source.isBlank() ? "{request.header.authorization}" : source;
        algorithm = algorithm == null ? null : algorithm.toUpperCase(java.util.Locale.ROOT);
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        clockSkewSeconds = clockSkewSeconds == null ? 60L : clockSkewSeconds;
    }

    public boolean isHmac() {
        return algorithm != null && HMAC.contains(algorithm);
    }

    @Override
    public List<String> validate() {
        Problems p = new Problems()
                .notBlank(algorithm, "algorithm")
                .require(algorithm == null || ASYMMETRIC.contains(algorithm) || HMAC.contains(algorithm),
                        "'algorithm' must be one of RS/PS/ES/HS 256/384/512")
                .range(clockSkewSeconds, 0, 3600, "clockSkewSeconds");
        if (isHmac()) {
            p.notBlank(secret, "secret")
                    .require(secret == null || secret.contains("{")
                                    || secret.getBytes(StandardCharsets.UTF_8).length >= 32,
                            "'secret' must be at least 32 bytes for HMAC algorithms");
        } else if (algorithm != null) {
            p.notBlank(jwksUri, "jwksUri").httpUrl(jwksUri, "jwksUri")
                    .require(jwksUri == null || !jwksUri.contains("{"), "'jwksUri' cannot use variables");
        }
        return p.list();
    }
}
