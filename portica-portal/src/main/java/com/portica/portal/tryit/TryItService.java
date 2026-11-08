package com.portica.portal.tryit;

import com.portica.common.runtime.ManagementApiClient;
import com.portica.portal.config.PortalProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Try-it: the request goes from this service to the gateway, so the browser needs no CORS access to the
 * gateway and the API's policies (keys, quotas, analytics) apply exactly as for real clients. Only APIs
 * published in the catalog can be called, at their deployed base path; headers that could confuse the
 * gateway or leak the portal's own context are dropped, redirects aren't followed and each developer
 * gets a small per-minute budget.
 */
@Service
public class TryItService {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> BLOCKED_HEADERS = Set.of("host", "connection", "content-length", "cookie",
            "transfer-encoding", "upgrade", "expect", "te", "trailer", "keep-alive", "proxy-authorization",
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
            "x-portica-internal-token", "x-portica-developer");
    private static final Set<String> RESPONSE_HEADERS_HIDDEN = Set.of("set-cookie", "connection", "transfer-encoding");

    public record TryItRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String environment,
                               @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String api,
                               @NotBlank String method,
                               @Pattern(regexp = "^(/[^\\s#]*)?$", message = "must start with / and contain no spaces") @Size(max = 2048) String path,
                               @Size(max = 50) Map<String, String> headers,
                               String body) {
    }

    public record TryItResponse(int status, Map<String, String> headers, String body, boolean truncated, long durationMs,
                                String url) {
    }

    private final ManagementApiClient management;
    private final HttpClient http;
    private final PortalProperties.TryIt limits;
    private final String gatewayUrl;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public TryItService(ManagementApiClient management, HttpClient tryItHttpClient, PortalProperties properties, Clock clock) {
        this.management = management;
        this.http = tryItHttpClient;
        this.limits = properties.tryIt();
        this.gatewayUrl = properties.gatewayUrl().replaceAll("/+$", "");
        this.clock = clock;
    }

    public TryItResponse send(String org, String developer, TryItRequest request) {
        String method = request.method().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported method " + request.method());
        }
        String path = request.path() == null ? "" : request.path();
        if (path.contains("/../") || path.endsWith("/..") || path.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not contain '..'");
        }
        byte[] body = request.body() == null ? new byte[0] : request.body().getBytes(StandardCharsets.UTF_8);
        if (body.length > limits.maxBodyBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large");
        }
        admit(developer);
        String basePath = basePath(org, request.api(), request.environment());
        URI uri = URI.create(gatewayUrl + "/" + org + "/" + request.environment() + basePath + path);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(limits.timeout())
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (request.headers() != null) {
            request.headers().forEach((name, value) -> {
                if (name != null && value != null && !BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                        && name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}") && value.length() <= 8192
                        && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
                    builder.header(name, value);
                }
            });
        }
        builder.header("X-Portica-Try-It", "developer-portal");
        Instant started = clock.instant();
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] content;
            boolean truncated;
            try (InputStream in = response.body()) {
                content = in.readNBytes(limits.maxBodyBytes() + 1);
                truncated = content.length > limits.maxBodyBytes();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!RESPONSE_HEADERS_HIDDEN.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":")) {
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values));
                }
            });
            String text = new String(content, 0, Math.min(content.length, limits.maxBodyBytes()), StandardCharsets.UTF_8);
            return new TryItResponse(response.statusCode(), headers, text, truncated,
                    Duration.between(started, clock.instant()).toMillis(), uri.toString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The gateway could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted");
        }
    }

    /** The deployed base path of a published API in the environment, from the catalog; 404 otherwise. */
    String basePath(String org, String api, String environment) {
        JsonNode catalog = management.get("/internal/v1/portal/organizations/" + org + "/catalog", JsonNode.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such organization"));
        for (JsonNode a : catalog.path("apis")) {
            if (a.path("name").asString("").equals(api)) {
                for (JsonNode d : a.path("deployments")) {
                    if (d.path("environment").asString("").equals(environment)) {
                        return d.path("basePath").asString("");
                    }
                }
            }
        }
        for (JsonNode s : catalog.path("mcpServers")) {
            if (s.path("name").asString("").equals(api)) {
                for (JsonNode e : s.path("endpoints")) {
                    if (e.path("environment").asString("").equals(environment)) {
                        return "/mcp/" + api;
                    }
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "'" + api + "' isn't published in " + environment);
    }

    /** Sliding one-minute window per developer. */
    private void admit(String developer) {
        Instant now = clock.instant();
        Deque<Instant> times = recent.computeIfAbsent(developer, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minusSeconds(60))) {
                times.pollFirst();
            }
            if (times.size() >= limits.requestsPerMinute()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many try-it requests; wait a minute");
            }
            times.addLast(now);
        }
        if (recent.size() > 10_000) {
            recent.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minusSeconds(60));
                }
            });
        }
    }
}
