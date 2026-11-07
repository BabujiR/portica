package com.portica.gateway.routing;

import com.portica.common.http.PorticaHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs first for every proxied request:
 * <ul>
 *   <li>assigns a correlation id ({@code X-Portica-Request-Id}), honouring a well-formed incoming one,
 *       and returns it to the client;</li>
 *   <li>removes client-supplied {@code X-Portica-Org/Env/Proxy/Revision} headers so backends can trust
 *       the values the gateway sets.</li>
 * </ul>
 */
@Component
public class RequestIdentityGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final List<String> GATEWAY_OWNED = List.of(PorticaHeaders.ORGANIZATION, PorticaHeaders.ENVIRONMENT,
            PorticaHeaders.PROXY, PorticaHeaders.REVISION);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(PorticaHeaders.REQUEST_ID);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    GATEWAY_OWNED.forEach(headers::remove);
                    headers.set(PorticaHeaders.REQUEST_ID, requestId);
                })
                .build();
        exchange.getResponse().getHeaders().set(PorticaHeaders.REQUEST_ID, requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
