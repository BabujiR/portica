package com.portica.gateway.policy.impl;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds JWT decoders (signature + claim validation) for OAuthV2 and VerifyJWT. */
final class JwtSupport {

    private JwtSupport() {
    }

    /** Asymmetric signature checked against a JWK Set; keys are cached and refreshed on unknown key ids. */
    static ReactiveJwtDecoder jwksDecoder(String jwksUri, String algorithm, OAuth2TokenValidator<Jwt> validator) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.from(algorithm))
                .build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    static ReactiveJwtDecoder hmacDecoder(String secret, String algorithm, OAuth2TokenValidator<Jwt> validator) {
        String jca = switch (algorithm) {
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> "HmacSHA256";
        };
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), jca))
                .macAlgorithm(MacAlgorithm.from(algorithm))
                .build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(long clockSkewSeconds, String issuer, List<String> audiences,
                                              String subject, Map<String, String> claims) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(clockSkewSeconds)));
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtIssuerValidator(issuer));
        }
        if (audiences != null && !audiences.isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("aud", aud -> audienceMatches(aud, audiences)));
        }
        if (subject != null && !subject.isBlank()) {
            validators.add(new JwtClaimValidator<Object>("sub", sub -> subject.equals(Objects.toString(sub, null))));
        }
        if (claims != null) {
            claims.forEach((claim, expected) ->
                    validators.add(new JwtClaimValidator<Object>(claim, v -> v != null && expected.equals(claimText(v)))));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static boolean audienceMatches(Object aud, List<String> accepted) {
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> accepted.contains(String.valueOf(v)));
        }
        return aud != null && accepted.contains(String.valueOf(aud));
    }

    /** Claim as text for variables: lists are joined with spaces. */
    static String claimText(Object value) {
        if (value instanceof Collection<?> values) {
            return String.join(" ", values.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    /** Strips a leading "Bearer " (any case). */
    static String bearer(String header) {
        if (header == null) {
            return "";
        }
        String h = header.strip();
        return h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).strip() : h;
    }

    static void publishClaims(com.portica.gateway.policy.MessageContext ctx, String prefix, Jwt jwt) {
        jwt.getClaims().forEach((claim, value) -> {
            if (value != null) {
                ctx.setVariable(prefix + "claim." + claim, claimText(value));
            }
        });
        ctx.setVariable(prefix + "subject", jwt.getSubject());
        ctx.setVariable(prefix + "issuer", jwt.getClaims().get("iss") == null ? null : String.valueOf(jwt.getClaims().get("iss")));
        ctx.setVariable(prefix + "valid", true);
    }
}
