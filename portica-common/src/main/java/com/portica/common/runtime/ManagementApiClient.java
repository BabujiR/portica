package com.portica.common.runtime;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Blocking client for portica-management's internal runtime API, authenticated with an OAuth client
 * credentials token from Keycloak (a client with the {@code portica-runtime} realm role). Used by the
 * AI gateway and the MCP service; the API gateway has its own reactive client.
 */
public class ManagementApiClient {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final URI baseUri;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    /** {@code tokenUri} null or blank: no authentication (local tests). */
    public ManagementApiClient(String baseUrl, String tokenUri, String clientId, String clientSecret, Duration timeout) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public JsonMapper json() {
        return json;
    }

    /** GET a JSON document; empty on 404. */
    public <T> Optional<T> get(String path, Class<T> type) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(resolve(path)).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        check(response, path);
        return Optional.of(json.readValue(response.body(), type));
    }

    /** POST without a body, returning JSON. */
    public <T> T post(String path, Class<T> type) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(resolve(path)).POST(HttpRequest.BodyPublishers.noBody()));
        check(response, path);
        return json.readValue(response.body(), type);
    }

    /** A raw response: status, content type and body as text. */
    public record Response(int status, String contentType, String body) {
    }

    /**
     * Any request, with a JSON body (or null) and extra headers; the response is returned whatever its
     * status, so callers can pass problems through (the developer portal forwards them to browsers).
     */
    public Response exchange(String method, String path, String jsonBody, java.util.Map<String, String> headers) {
        HttpRequest.BodyPublisher body = jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path)).method(method, body);
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        HttpResponse<String> response = send(builder);
        return new Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse("application/json"), response.body());
    }

    private URI resolve(String path) {
        return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        builder.timeout(timeout).header("Accept", "application/json");
        String bearer = bearer();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ManagementApiException(0, "Management API unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementApiException(0, "Interrupted");
        }
    }

    private void check(HttpResponse<String> response, String path) {
        if (response.statusCode() / 100 != 2) {
            String detail = null;
            try {
                JsonNode problem = json.readTree(response.body());
                detail = problem.path("detail").asString(null);
            } catch (RuntimeException ignored) {
                // not JSON
            }
            throw new ManagementApiException(response.statusCode(), "Management API " + path + " returned "
                    + response.statusCode() + (detail == null ? "" : ": " + detail));
        }
    }

    private synchronized String bearer() {
        if (tokenUri == null || tokenUri.isBlank()) {
            return null;
        }
        if (token != null && tokenExpires.isAfter(Instant.now().plus(REFRESH_MARGIN))) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret="
                + enc(clientSecret == null ? "" : clientSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri)).timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ManagementApiException(response.statusCode(), "Token endpoint returned " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            token = body.path("access_token").asString();
            tokenExpires = Instant.now().plusSeconds(body.path("expires_in").asLong(60));
            return token;
        } catch (IOException e) {
            throw new ManagementApiException(0, "Token endpoint unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementApiException(0, "Interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public static class ManagementApiException extends RuntimeException {
        private final int status;

        public ManagementApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
