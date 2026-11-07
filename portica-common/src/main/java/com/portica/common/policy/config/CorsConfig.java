package com.portica.common.policy.config;

import java.util.List;

/**
 * CORS: answers browser preflight requests and adds CORS headers to responses.
 *
 * @param allowOrigins     allowed origins, e.g. {@code https://app.example.com}; {@code *} for any (required)
 * @param allowMethods     default GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS
 * @param allowHeaders     default {@code *} (echoes the requested headers)
 * @param exposeHeaders    response headers readable by browser code
 * @param maxAgeSeconds    preflight cache time (default 1800)
 * @param allowCredentials allow cookies/authorization (default false); the request origin is echoed
 */
public record CorsConfig(List<String> allowOrigins, List<String> allowMethods, List<String> allowHeaders,
                         List<String> exposeHeaders, Long maxAgeSeconds, Boolean allowCredentials)
        implements PolicyConfig {

    public CorsConfig {
        allowOrigins = allowOrigins == null ? List.of() : List.copyOf(allowOrigins);
        allowMethods = allowMethods == null || allowMethods.isEmpty()
                ? List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") : List.copyOf(allowMethods);
        allowHeaders = allowHeaders == null || allowHeaders.isEmpty() ? List.of("*") : List.copyOf(allowHeaders);
        exposeHeaders = exposeHeaders == null ? List.of() : List.copyOf(exposeHeaders);
        maxAgeSeconds = maxAgeSeconds == null ? 1800L : maxAgeSeconds;
        allowCredentials = allowCredentials != null && allowCredentials;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .require(!allowOrigins.isEmpty(), "'allowOrigins' must list at least one origin (or \"*\")")
                .range(maxAgeSeconds, 0, 86_400, "maxAgeSeconds")
                .headerNames(exposeHeaders, "exposeHeaders")
                .list();
    }
}
