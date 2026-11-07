package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.CorsConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.ProxyResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CORS. Preflight requests ({@code OPTIONS} with {@code Access-Control-Request-Method}) are answered
 * directly (204, or 403 for a disallowed origin/method) without calling the target. For other requests
 * from an allowed origin, CORS headers are added to whatever response is returned, including errors,
 * so browser code can read them.
 */
public final class CorsPolicy implements Policy {

    private final String name;
    private final CorsConfig config;
    private final boolean anyOrigin;
    private final Set<String> origins;
    private final Set<String> methods;
    private final boolean anyHeader;

    public CorsPolicy(String name, CorsConfig config) {
        this.name = name;
        this.config = config;
        this.anyOrigin = config.allowOrigins().contains("*");
        this.origins = config.allowOrigins().stream().map(o -> o.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.methods = config.allowMethods().stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        this.anyHeader = config.allowHeaders().contains("*");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        HttpHeaders request = ctx.request().headers();
        String origin = request.getFirst(HttpHeaders.ORIGIN);
        if (origin == null) {
            return Mono.empty(); // not a cross-origin browser request
        }
        boolean originAllowed = anyOrigin || origins.contains(origin.toLowerCase(Locale.ROOT));
        String requestedMethod = request.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        boolean preflight = ctx.request().method() == HttpMethod.OPTIONS && requestedMethod != null;

        if (preflight) {
            HttpHeaders headers = new HttpHeaders();
            if (!originAllowed || !methods.contains(requestedMethod.toUpperCase(Locale.ROOT))) {
                ctx.respond(new ProxyResponse(403, headers, null));
                return Mono.empty();
            }
            allowOrigin(headers, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", config.allowMethods()));
            String requestedHeaders = request.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            String allowHeaders = anyHeader ? requestedHeaders : String.join(", ", config.allowHeaders());
            if (allowHeaders != null && !allowHeaders.isBlank()) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, allowHeaders);
            }
            headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, Long.toString(config.maxAgeSeconds()));
            ctx.respond(new ProxyResponse(204, headers, null));
            return Mono.empty();
        }

        if (originAllowed) {
            HttpHeaders pending = ctx.pendingResponseHeaders();
            allowOrigin(pending, origin);
            if (!config.exposeHeaders().isEmpty()) {
                pending.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", config.exposeHeaders()));
            }
        }
        return Mono.empty();
    }

    private void allowOrigin(HttpHeaders headers, String origin) {
        boolean echo = !anyOrigin || config.allowCredentials();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, echo ? origin : "*");
        if (echo) {
            headers.set(HttpHeaders.VARY, "Origin");
        }
        if (config.allowCredentials()) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.CORS;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new CorsPolicy(name, (CorsConfig) config);
        }
    }
}
