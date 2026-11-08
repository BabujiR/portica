package com.portica.management.mcp;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.common.error.ResourceNotFoundException;
import com.portica.management.kvm.KvmCipher;
import com.portica.management.mcp.McpDtos.AuthorizeResponse;
import com.portica.management.mcp.McpDtos.RemoteAuth;
import com.portica.management.mcp.McpDtos.RemoteSpec;
import com.portica.management.mcp.McpServer.OAuthMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portica as an OAuth 2.1 client of remote MCP servers, following the MCP authorization spec:
 * <ol>
 *   <li>Discovery: the server's 401 {@code WWW-Authenticate: Bearer resource_metadata=...} (or the
 *       well-known URL) gives the protected resource metadata (RFC 9728), which names the authorization
 *       server; its metadata (RFC 8414 / OpenID discovery) gives the endpoints.</li>
 *   <li>Client credentials for machine-to-machine servers; authorization code with PKCE (S256 only) when
 *       an admin must sign in. Every token request carries the resource indicator (RFC 8707).</li>
 *   <li>Tokens are stored encrypted and refreshed here, under a row lock, so rotated refresh tokens are
 *       never used twice. portica-mcp asks for a current access token and never sees refresh tokens.</li>
 * </ol>
 */
@Service
public class McpOAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);
    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpServerRepository servers;
    private final KvmCipher cipher;
    private final JdbcClient jdbc;
    private final McpProperties properties;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public McpOAuthService(McpServerRepository servers, KvmCipher cipher, JdbcClient jdbc, McpProperties properties) {
        this.servers = servers;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** The access token to send to a remote server right now, or null when it needs none. */
    public record AccessToken(String token, Instant expiresAt) {
    }

    // ------------------------------------------------------------------ tokens

    /** Current token for runtime use: cached while valid, else refreshed or fetched (client credentials). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccessToken accessToken(UUID serverId) {
        McpServer server = servers.lockById(serverId)
                .orElseThrow(() -> new ResourceNotFoundException("McpServer", serverId.toString()));
        RemoteSpec remote = server.spec().remote();
        if (remote == null || remote.auth() == RemoteAuth.NONE) {
            return new AccessToken(null, null);
        }
        if (remote.auth() == RemoteAuth.BEARER) {
            return new AccessToken(server.getSecretEncrypted() == null ? null
                    : cipher.decrypt(server.getSecretEncrypted(), aad(server, "secret")), null);
        }
        Instant now = Instant.now();
        if (server.getAccessTokenEncrypted() != null
                && (server.getTokenExpiresAt() == null || server.getTokenExpiresAt().isAfter(now.plus(REFRESH_MARGIN)))) {
            return new AccessToken(cipher.decrypt(server.getAccessTokenEncrypted(), aad(server, "access")),
                    server.getTokenExpiresAt());
        }
        OAuthMetadata metadata = metadata(server);
        TokenResult result;
        if (server.getRefreshTokenEncrypted() != null) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", cipher.decrypt(server.getRefreshTokenEncrypted(), aad(server, "refresh")));
            form.put("resource", metadata.resource());
            result = tokenRequest(server, metadata, form);
        } else if (remote.auth() == RemoteAuth.OAUTH_CLIENT_CREDENTIALS) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "client_credentials");
            if (remote.scopes() != null && !remote.scopes().isEmpty()) {
                form.put("scope", String.join(" ", remote.scopes()));
            }
            form.put("resource", metadata.resource());
            result = tokenRequest(server, metadata, form);
        } else {
            throw new BusinessRuleViolationException("MCP server '%s' isn't connected: sign in to its authorization server first"
                    .formatted(server.getName()));
        }
        store(server, result);
        return new AccessToken(result.accessToken(), server.getTokenExpiresAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void disconnect(McpServer server) {
        server.clearTokens();
    }

    // ------------------------------------------------------------------ authorization code + PKCE

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthorizeResponse startAuthorization(McpServer server, String actor) {
        RemoteSpec remote = server.spec().remote();
        if (remote == null || remote.auth() != RemoteAuth.OAUTH_AUTHORIZATION_CODE) {
            throw new BusinessRuleViolationException("Sign-in applies to remote MCP servers with auth OAUTH_AUTHORIZATION_CODE");
        }
        OAuthMetadata metadata = refreshMetadata(server);
        if (metadata.authorizationEndpoint() == null) {
            throw new BusinessRuleViolationException("The authorization server doesn't advertise an authorization endpoint");
        }
        if (metadata.codeChallengeMethods() != null && !metadata.codeChallengeMethods().isEmpty()
                && !metadata.codeChallengeMethods().contains("S256")) {
            throw new BusinessRuleViolationException("The authorization server doesn't support PKCE with S256, which OAuth 2.1 requires");
        }
        String verifier = randomToken(48);
        String state = randomToken(24);
        Instant expires = Instant.now().plus(STATE_TTL);
        jdbc.sql("DELETE FROM mcp_oauth_states WHERE expires_at < :now").param("now", Timestamp.from(Instant.now())).update();
        jdbc.sql("""
                        INSERT INTO mcp_oauth_states (state, server_id, code_verifier, redirect_uri, created_by, expires_at)
                        VALUES (:state, :server, :verifier, :redirect, :actor, :expires)
                        """)
                .param("state", state).param("server", server.getId()).param("verifier", verifier)
                .param("redirect", properties.redirectUri()).param("actor", actor)
                .param("expires", Timestamp.from(expires)).update();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", remote.clientId());
        query.put("redirect_uri", properties.redirectUri());
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        query.put("state", state);
        if (remote.scopes() != null && !remote.scopes().isEmpty()) {
            query.put("scope", String.join(" ", remote.scopes()));
        }
        query.put("resource", metadata.resource());
        String url = metadata.authorizationEndpoint() + (metadata.authorizationEndpoint().contains("?") ? "&" : "?") + form(query);
        return new McpDtos.AuthorizeResponse(url, expires);
    }

    /** Finishes the sign-in (the browser lands here); returns the console URL to send the admin back to. */
    @Transactional
    public String completeAuthorization(String state, String code, String error, String errorDescription) {
        Optional<Map<String, Object>> pending = state == null ? Optional.empty() : jdbc.sql("""
                        DELETE FROM mcp_oauth_states WHERE state = :state
                        RETURNING server_id, code_verifier, redirect_uri, expires_at
                        """)
                .param("state", state).query().listOfRows().stream().findFirst();
        if (pending.isEmpty()) {
            return properties.consoleUrl() + "/?mcpOAuth=" + enc("The sign-in link expired or was already used. Try again.");
        }
        Map<String, Object> row = pending.get();
        McpServer server = servers.findById((UUID) row.get("server_id")).orElse(null);
        if (server == null) {
            return properties.consoleUrl() + "/?mcpOAuth=" + enc("The MCP server no longer exists.");
        }
        String back = properties.consoleUrl() + "/o/" + server.getOrganization().getName() + "/mcp/" + server.getName();
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            return back + "?oauth=error&message=" + enc("The sign-in took too long. Try again.");
        }
        if (error != null || code == null) {
            return back + "?oauth=error&message=" + enc("Authorization failed: " + (error == null ? "no code" : error)
                    + (errorDescription == null ? "" : " (" + errorDescription + ")"));
        }
        try {
            OAuthMetadata metadata = metadata(server);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", (String) row.get("redirect_uri"));
            form.put("code_verifier", (String) row.get("code_verifier"));
            form.put("resource", metadata.resource());
            store(server, tokenRequest(server, metadata, form));
            return back + "?oauth=connected";
        } catch (BusinessRuleViolationException e) {
            return back + "?oauth=error&message=" + enc(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ discovery

    /** Stored metadata, discovering it first when missing. */
    OAuthMetadata metadata(McpServer server) {
        OAuthMetadata metadata = server.oauthMetadata();
        return metadata != null && metadata.tokenEndpoint() != null ? metadata : refreshMetadata(server);
    }

    OAuthMetadata refreshMetadata(McpServer server) {
        RemoteSpec remote = server.spec().remote();
        OAuthMetadata metadata = discover(remote.url(), remote.authorizationServer());
        server.storeOAuthMetadata(metadata);
        return metadata;
    }

    /** RFC 9728 then RFC 8414 / OpenID discovery. */
    public OAuthMetadata discover(String serverUrl, String issuerOverride) {
        String resource = serverUrl;
        String issuer = blank(issuerOverride) ? null : issuerOverride.strip();
        if (issuer == null) {
            JsonNode prm = protectedResourceMetadata(serverUrl);
            if (prm != null) {
                if (prm.path("resource").isString()) {
                    resource = prm.path("resource").asString();
                }
                if (prm.path("authorization_servers").isArray() && !prm.path("authorization_servers").isEmpty()) {
                    issuer = prm.path("authorization_servers").get(0).asString();
                }
            }
        }
        if (issuer == null) {
            URI u = URI.create(serverUrl);
            issuer = u.getScheme() + "://" + u.getRawAuthority();   // servers predating RFC 9728 support
        }
        JsonNode as = authorizationServerMetadata(issuer);
        if (as == null || !as.path("token_endpoint").isString()) {
            throw new BusinessRuleViolationException("Couldn't find OAuth authorization server metadata for " + issuer);
        }
        List<String> methods = new ArrayList<>();
        as.path("code_challenge_methods_supported").forEach(m -> methods.add(m.asString()));
        return new OAuthMetadata(resource, as.path("issuer").asString(issuer), text(as, "authorization_endpoint"),
                text(as, "token_endpoint"), methods);
    }

    private JsonNode protectedResourceMetadata(String serverUrl) {
        List<String> candidates = new ArrayList<>();
        try {
            HttpRequest probe = HttpRequest.newBuilder(URI.create(serverUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}"))
                    .build();
            HttpResponse<String> response = http.send(probe, HttpResponse.BodyHandlers.ofString());
            Optional<String> header = response.headers().firstValue("WWW-Authenticate");
            if (header.isPresent()) {
                Matcher m = RESOURCE_METADATA.matcher(header.get());
                if (m.find()) {
                    candidates.add(m.group(1));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessRuleViolationException("MCP server " + serverUrl + " is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleViolationException("Interrupted");
        }
        URI u = URI.create(serverUrl);
        String origin = u.getScheme() + "://" + u.getRawAuthority();
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        if (!path.isEmpty() && !path.equals("/")) {
            candidates.add(origin + "/.well-known/oauth-protected-resource" + path);
        }
        candidates.add(origin + "/.well-known/oauth-protected-resource");
        for (String url : candidates) {
            JsonNode node = getJson(url);
            if (node != null && node.isObject()) {
                return node;
            }
        }
        return null;
    }

    private JsonNode authorizationServerMetadata(String issuer) {
        URI u = URI.create(issuer);
        String origin = u.getScheme() + "://" + u.getRawAuthority();
        String path = u.getRawPath() == null || u.getRawPath().equals("/") ? "" : u.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        List<String> candidates = List.of(
                origin + "/.well-known/oauth-authorization-server" + path,
                origin + "/.well-known/openid-configuration" + path,
                origin + path + "/.well-known/openid-configuration");
        for (String url : candidates.stream().distinct().toList()) {
            JsonNode node = getJson(url);
            if (node != null && node.isObject() && node.has("token_endpoint")) {
                return node;
            }
        }
        return null;
    }

    private JsonNode getJson(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------ token endpoint

    record TokenResult(String accessToken, String refreshToken, Long expiresIn, String scope) {
    }

    private TokenResult tokenRequest(McpServer server, OAuthMetadata metadata, Map<String, String> form) {
        RemoteSpec remote = server.spec().remote();
        String secret = server.getSecretEncrypted() == null ? null : cipher.decrypt(server.getSecretEncrypted(), aad(server, "secret"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(metadata.tokenEndpoint()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        Map<String, String> body = new LinkedHashMap<>(form);
        if (secret != null) {
            String basic = enc(remote.clientId()) + ":" + enc(secret);   // RFC 6749 2.3.1
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        } else {
            body.put("client_id", remote.clientId());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(form(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BusinessRuleViolationException("The token endpoint is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessRuleViolationException("Interrupted");
        }
        JsonNode json;
        try {
            json = JSON.readTree(response.body());
        } catch (RuntimeException e) {
            json = null;
        }
        if (response.statusCode() != 200 || json == null || !json.path("access_token").isString()) {
            String error = json == null ? "HTTP " + response.statusCode()
                    : json.path("error").asString("HTTP " + response.statusCode())
                    + (json.path("error_description").isString() ? ": " + json.path("error_description").asString() : "");
            log.warn("Token request for MCP server {} failed: {}", server.getName(), error);
            if ("invalid_grant".equals(json == null ? null : json.path("error").asString(null))) {
                server.clearTokens();   // refresh token revoked or expired: an admin must sign in again
            }
            throw new BusinessRuleViolationException("The authorization server refused the token request (" + error + ")");
        }
        return new TokenResult(json.path("access_token").asString(), text(json, "refresh_token"),
                json.path("expires_in").isNumber() ? json.path("expires_in").asLong() : null, text(json, "scope"));
    }

    private void store(McpServer server, TokenResult result) {
        Instant expires = result.expiresIn() == null ? null : Instant.now().plusSeconds(result.expiresIn());
        // keep the old refresh token when the server doesn't rotate it
        String refresh = result.refreshToken() != null
                ? cipher.encrypt(result.refreshToken(), aad(server, "refresh"))
                : server.getRefreshTokenEncrypted();
        server.storeTokens(cipher.encrypt(result.accessToken(), aad(server, "access")), refresh, expires,
                result.scope());
    }

    // ------------------------------------------------------------------ helpers

    static String aad(McpServer server, String kind) {
        return "mcp-server:" + server.getId() + ":" + kind;
    }

    String encryptSecret(McpServer server, String plain) {
        cipher.requireEnabled();
        return cipher.encrypt(plain, aad(server, "secret"));
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** PKCE S256 challenge (RFC 7636). */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(Map<String, String> values) {
        StringJoiner joiner = new StringJoiner("&");
        values.forEach((k, v) -> {
            if (v != null) {
                joiner.add(enc(k) + "=" + enc(v));
            }
        });
        return joiner.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
