package com.portica.mcp.runtime;

import com.portica.common.runtime.ManagementApiClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access tokens for remote MCP servers. The management plane holds the OAuth client (and refresh
 * tokens) and refreshes them; this caches what it hands out until shortly before expiry.
 */
@Component
public class RemoteTokens {

    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final Duration NO_EXPIRY_TTL = Duration.ofMinutes(5);

    private record Cached(String token, Instant reuseUntil) {
    }

    private final ManagementApiClient management;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RemoteTokens(ManagementApiClient management) {
        this.management = management;
    }

    public String token(String org, String server) {
        String key = org + "/" + server;
        Cached cached = cache.get(key);
        if (cached != null && cached.reuseUntil().isAfter(Instant.now())) {
            return cached.token();
        }
        McpRuntime.Token token = management.post("/internal/v1/runtime/organizations/" + org + "/mcp-servers/" + server
                + "/token", McpRuntime.Token.class);
        Instant until = token.expiresAt() == null ? Instant.now().plus(NO_EXPIRY_TTL) : token.expiresAt().minus(MARGIN);
        cache.put(key, new Cached(token.accessToken(), until));
        return token.accessToken();
    }

    /** After the remote server refused a token. */
    public void invalidate(String org, String server) {
        cache.remove(org + "/" + server);
    }
}
