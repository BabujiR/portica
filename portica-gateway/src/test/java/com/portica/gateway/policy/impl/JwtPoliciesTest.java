package com.portica.gateway.policy.impl;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.portica.common.policy.config.OAuthV2Config;
import com.portica.common.policy.config.VerifyJwtConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static org.assertj.core.api.Assertions.assertThat;

class JwtPoliciesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes
    private static RSAKey rsaKey;
    private static HttpServer jwks;
    private static String jwksUri;

    @BeforeAll
    static void startJwks() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] body = new JWKSet(rsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        jwksUri = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks";
    }

    @AfterAll
    static void stopJwks() {
        jwks.stop(0);
    }

    // ------------------------------------------------------------------ VerifyJWT (HMAC)

    @Test
    void verifyJwtAcceptsValidHmacTokenAndPublishesClaims() throws Exception {
        VerifyJwtPolicy policy = new VerifyJwtPolicy("jwt", new VerifyJwtConfig(null, "HS256", null, SECRET,
                "https://issuer.example.com", List.of("orders"), null, Map.of("tier", "gold"), null));
        String token = hmacToken(claims("https://issuer.example.com", "orders").claim("tier", "gold").build());

        MessageContext ctx = request("GET", "/").header("Authorization", "Bearer " + token).build();
        assertThat(run(policy, ctx)).isNull();
        assertThat(ctx.variable("jwt.jwt.claim.sub")).isEqualTo("user-42");
        assertThat(ctx.variable("jwt.jwt.claim.tier")).isEqualTo("gold");
        assertThat(ctx.variable("jwt.jwt.valid")).isEqualTo(true);
    }

    @Test
    void verifyJwtRejectsWrongClaimsSignatureExpiryAndMissingTokens() throws Exception {
        VerifyJwtPolicy policy = new VerifyJwtPolicy("jwt", new VerifyJwtConfig(null, "HS256", null, SECRET,
                "https://issuer.example.com", List.of("orders"), null, null, 0L));

        assertThat(fault(policy, hmacToken(claims("https://evil.example.com", "orders").build()))).isEqualTo("JwtInvalid");
        assertThat(fault(policy, hmacToken(claims("https://issuer.example.com", "billing").build()))).isEqualTo("JwtInvalid");
        assertThat(fault(policy, hmacToken(claims("https://issuer.example.com", "orders")
                .expirationTime(Date.from(Instant.now().minusSeconds(60))).build()))).isEqualTo("JwtInvalid");
        String tampered = hmacToken(claims("https://issuer.example.com", "orders").build());
        assertThat(fault(policy, tampered.substring(0, tampered.length() - 3) + "abc")).isEqualTo("JwtInvalid");
        assertThat(run(policy, request("GET", "/").build()).faultName()).isEqualTo("JwtMissing");
    }

    // ------------------------------------------------------------------ OAuthV2 (RS256 via JWKS)

    @Test
    void oauthAcceptsRs256TokenFromJwksAndChecksScopes() throws Exception {
        OAuthV2Policy policy = new OAuthV2Policy("oauth", new OAuthV2Config(null, jwksUri,
                "https://idp.example.com", null, List.of("orders:read"), null));
        String token = rsaToken(claims("https://idp.example.com", "orders")
                .claim("scope", "openid orders:read").claim("azp", "mobile-client").build());

        MessageContext ctx = request("GET", "/").header("Authorization", "Bearer " + token).build();
        assertThat(run(policy, ctx)).isNull();
        assertThat(ctx.variable("oauthv2.oauth.client_id")).isEqualTo("mobile-client");
        assertThat(ctx.variable("oauthv2.oauth.subject")).isEqualTo("user-42");
    }

    @Test
    void oauthFaultsUseRfc6750Headers() throws Exception {
        OAuthV2Policy policy = new OAuthV2Policy("oauth", new OAuthV2Config(null, jwksUri,
                "https://idp.example.com", null, List.of("orders:write"), null));

        PolicyFault missing = run(policy, request("GET", "/").build());
        assertThat(missing.faultName()).isEqualTo("MissingAccessToken");
        assertThat(missing.headers().getFirst("WWW-Authenticate")).isEqualTo("Bearer");

        String readOnly = rsaToken(claims("https://idp.example.com", "x").claim("scope", "orders:read").build());
        PolicyFault scope = run(policy, request("GET", "/").header("Authorization", "Bearer " + readOnly).build());
        assertThat(scope.faultName()).isEqualTo("InsufficientScope");
        assertThat(scope.status()).isEqualTo(403);

        String hmac = hmacToken(claims("https://idp.example.com", "x").claim("scope", "orders:write").build());
        PolicyFault invalid = run(policy, request("GET", "/").header("Authorization", "Bearer " + hmac).build());
        assertThat(invalid.faultName()).isEqualTo("InvalidAccessToken");
        assertThat(invalid.headers().getFirst("WWW-Authenticate")).contains("invalid_token");
    }

    // ------------------------------------------------------------------ helpers

    private static JWTClaimsSet.Builder claims(String issuer, String audience) {
        return new JWTClaimsSet.Builder().subject("user-42").issuer(issuer).audience(audience)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    private static String hmacToken(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String rsaToken(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private static String fault(VerifyJwtPolicy policy, String token) {
        PolicyFault f = run(policy, request("GET", "/").header("Authorization", "Bearer " + token).build());
        return f == null ? null : f.faultName();
    }
}
