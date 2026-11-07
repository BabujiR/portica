package com.portica.gateway.oauth;

import com.portica.common.security.Hashing;
import com.portica.gateway.policy.credentials.AppCredential;
import com.portica.gateway.policy.credentials.CredentialRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuth 2.0 client credentials grant (RFC 6749 section 4.4) and token revocation (RFC 7009) for
 * developer apps, served by the gateway at {@code /{org}/{env}/oauth2/token} and {@code .../revoke}.
 * <p>
 * Clients authenticate with their consumer key and secret, preferably with HTTP Basic, or with
 * {@code client_id}/{@code client_secret} form parameters. Issued tokens are opaque random strings;
 * only their SHA-256 hash is stored. The {@code OAuthV2} policy (without {@code jwksUri}) verifies them.
 */
@Component
public class TokenEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpoint.class);
    private static final String CLIENT_CREDENTIALS = "client_credentials";

    private final CredentialRegistry credentials;
    private final AccessTokenStore tokens;
    private final OAuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public TokenEndpoint(CredentialRegistry credentials, AccessTokenStore tokens, OAuthProperties properties) {
        this(credentials, tokens, properties, Clock.systemUTC());
    }

    TokenEndpoint(CredentialRegistry credentials, AccessTokenStore tokens, OAuthProperties properties, Clock clock) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<ServerResponse> token(ServerRequest request) {
        String org = request.pathVariable("org");
        String env = request.pathVariable("env");
        if (!credentials.serves(org, env)) {
            return ServerResponse.notFound().build();
        }
        if (!isForm(request)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "Use application/x-www-form-urlencoded", false);
        }
        return request.formData().flatMap(form -> {
            OAuthError duplicate = duplicates(form, "grant_type", "scope", "client_id", "client_secret");
            if (duplicate != null) {
                return duplicate.response();
            }
            String grantType = form.getFirst("grant_type");
            if (grantType == null || grantType.isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "invalid_request", "grant_type is required", false);
            }
            if (!CLIENT_CREDENTIALS.equals(grantType)) {
                return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported", false);
            }
            ClientAuthentication client = authenticate(request, form, org, env);
            if (client.error() != null) {
                return client.error().response();
            }
            AppCredential app = client.app();
            if (app.products().isEmpty()) {
                return error(HttpStatus.BAD_REQUEST, "unauthorized_client",
                        "The app holds no approved API product in this environment", false);
            }
            Set<String> allowed = app.scopes();
            Set<String> requested = scopes(form.getFirst("scope"));
            if (!allowed.containsAll(requested)) {
                return error(HttpStatus.BAD_REQUEST, "invalid_scope", "A requested scope isn't granted to the app", false);
            }
            List<String> granted = new ArrayList<>(requested.isEmpty() ? allowed : requested);

            String token = newToken();
            Instant now = clock.instant();
            Duration ttl = properties.tokenTtl();
            AccessTokenRecord record = new AccessTokenRecord(org, env, app.credentialId(), Hashing.sha256Hex(app.clientId()),
                    app.clientId(), granted, now, now.plus(ttl));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", token);
            body.put("token_type", "Bearer");
            body.put("expires_in", ttl.toSeconds());
            if (!granted.isEmpty()) {
                body.put("scope", String.join(" ", granted));
            }
            return tokens.save(Hashing.sha256Hex(token), record, ttl)
                    .then(noStore(ServerResponse.ok()).contentType(MediaType.APPLICATION_JSON).bodyValue(body))
                    .onErrorResume(e -> {
                        log.error("Could not store an access token for app {}: {}", app.appName(), e.toString());
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable",
                                "Tokens can't be issued right now", false);
                    });
        });
    }

    /** RFC 7009: always 200 for a valid client, whether or not the token existed. */
    public Mono<ServerResponse> revoke(ServerRequest request) {
        String org = request.pathVariable("org");
        String env = request.pathVariable("env");
        if (!credentials.serves(org, env)) {
            return ServerResponse.notFound().build();
        }
        if (!isForm(request)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "Use application/x-www-form-urlencoded", false);
        }
        return request.formData().flatMap(form -> {
            ClientAuthentication client = authenticate(request, form, org, env);
            if (client.error() != null) {
                return client.error().response();
            }
            String token = form.getFirst("token");
            if (token == null || token.isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "invalid_request", "token is required", false);
            }
            String hash = Hashing.sha256Hex(token);
            String clientKeyHash = Hashing.sha256Hex(client.app().clientId());
            return tokens.find(hash)
                    .filter(t -> t.keyHash().equals(clientKeyHash)) // a client may only revoke its own tokens
                    .flatMap(t -> tokens.delete(hash))
                    .then(noStore(ServerResponse.ok()).build());
        });
    }

    // ----------------------------------------------------------------- client authentication

    private record ClientAuthentication(AppCredential app, OAuthError error) {
    }

    private ClientAuthentication authenticate(ServerRequest request, MultiValueMap<String, String> form,
                                              String org, String env) {
        String header = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        boolean basic = header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
        String id;
        String secret;
        if (basic) {
            if (form.containsKey("client_secret")) {
                return failed(new OAuthError(HttpStatus.BAD_REQUEST, "invalid_request",
                        "Use one client authentication method", false));
            }
            String[] pair = decodeBasic(header.substring(6).strip());
            if (pair == null) {
                return failed(invalidClient(true));
            }
            id = pair[0];
            secret = pair[1];
        } else {
            id = form.getFirst("client_id");
            secret = form.getFirst("client_secret");
        }
        if (id == null || id.isBlank() || secret == null || secret.isEmpty()) {
            return failed(invalidClient(basic));
        }
        AppCredential app = credentials.index(org, env).findByKey(id).orElse(null);
        if (app == null || app.secretHash() == null || !Hashing.matches(app.secretHash(), Hashing.sha256Hex(secret))
                || app.expired(clock.instant())) {
            log.debug("Client authentication failed at {}/{}", org, env);
            return failed(invalidClient(basic));
        }
        return new ClientAuthentication(app, null);
    }

    private static ClientAuthentication failed(OAuthError error) {
        return new ClientAuthentication(null, error);
    }

    private static OAuthError invalidClient(boolean basic) {
        return new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed", basic);
    }

    /** RFC 6749 2.3.1: id and secret are form-encoded before Basic encoding. */
    static String[] decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            return new String[]{URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- helpers

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Set<String> scopes(String scope) {
        Set<String> result = new LinkedHashSet<>();
        if (scope != null && !scope.isBlank()) {
            result.addAll(Arrays.asList(scope.strip().split("\\s+")));
        }
        return result;
    }

    private static boolean isForm(ServerRequest request) {
        return request.headers().contentType()
                .map(t -> MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(t))
                .orElse(false);
    }

    private static OAuthError duplicates(MultiValueMap<String, String> form, String... names) {
        for (String name : names) {
            List<String> values = form.get(name);
            if (values != null && values.size() > 1) {
                return new OAuthError(HttpStatus.BAD_REQUEST, "invalid_request", name + " is repeated", false);
            }
        }
        return null;
    }

    private static ServerResponse.BodyBuilder noStore(ServerResponse.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore()).header("Pragma", "no-cache");
    }

    private static Mono<ServerResponse> error(HttpStatus status, String code, String description, boolean basic) {
        return new OAuthError(status, code, description, basic).response();
    }

    /** An RFC 6749 section 5.2 error response. */
    record OAuthError(HttpStatus status, String code, String description, boolean basicUsed) {

        Mono<ServerResponse> response() {
            ServerResponse.BodyBuilder builder = noStore(ServerResponse.status(status)).contentType(MediaType.APPLICATION_JSON);
            if (status == HttpStatus.UNAUTHORIZED) {
                builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"portica\"");
            }
            return builder.bodyValue(Map.of("error", code, "error_description", description));
        }
    }
}
