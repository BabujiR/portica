package com.portica.gateway.routing;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Lets CORS preflight requests reach the API proxy's own policies.
 * <p>
 * WebFlux handler mappings (including the gateway's) answer every preflight request themselves
 * with an empty response, before any gateway filter runs. CORS is a per-proxy policy in Portica,
 * so this filter hides the preflight marker ({@code Access-Control-Request-Method}) in an internal
 * header, and {@code PolicyEngine} restores it before the proxy's flows run. A copy of the
 * internal header sent by a client is always removed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PreflightPassThroughWebFilter implements WebFilter {

    /** Internal header carrying the original {@code Access-Control-Request-Method}. */
    public static final String PREFLIGHT_METHOD = "X-Portica-Preflight-Method";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean preflight = CorsUtils.isPreFlightRequest(request) && !request.getPath().value().startsWith("/actuator");
        if (!preflight && request.getHeaders().getFirst(PREFLIGHT_METHOD) == null) {
            return chain.filter(exchange);
        }
        String method = preflight ? request.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) : null;
        ServerHttpRequest mutated = request.mutate().headers(h -> {
            h.remove(PREFLIGHT_METHOD);
            if (method != null) {
                h.remove(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
                h.set(PREFLIGHT_METHOD, method);
            }
        }).build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** Puts the hidden preflight marker back (on the gateway's own copy of the headers). */
    public static void restore(HttpHeaders headers) {
        String method = headers.getFirst(PREFLIGHT_METHOD);
        if (method != null) {
            headers.remove(PREFLIGHT_METHOD);
            headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        }
    }
}
