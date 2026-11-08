package com.portica.gateway.oauth;

import com.portica.common.http.PorticaPaths;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth discovery documents MCP clients use to find out how to get a token (Phase 7):
 * <ul>
 *   <li>{@code /.well-known/oauth-authorization-server/{org}/{env}}: authorization server metadata
 *       (RFC 8414) for the environment's token endpoint; the issuer is {@code {gateway}/{org}/{env}}.</li>
 *   <li>{@code /.well-known/oauth-protected-resource/{org}/{env}/mcp/{name}}: protected resource metadata
 *       (RFC 9728) for an MCP server, naming that issuer.</li>
 * </ul>
 * Portica issues tokens with the client credentials grant, so these describe machine-to-machine access.
 */
@Component
public class OAuthMetadataEndpoint {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    public Mono<ServerResponse> authorizationServer(ServerRequest request) {
        String issuer = baseUrl(request.exchange().getRequest()) + "/" + request.pathVariable("org") + "/"
                + request.pathVariable("env");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("token_endpoint", issuer + PorticaPaths.TOKEN);
        body.put("revocation_endpoint", issuer + PorticaPaths.REVOKE);
        body.put("grant_types_supported", List.of("client_credentials"));
        body.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        body.put("revocation_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        body.put("response_types_supported", List.of("token"));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CACHE).bodyValue(body);
    }

    public Mono<ServerResponse> protectedResource(ServerRequest request) {
        String base = baseUrl(request.exchange().getRequest());
        String issuer = base + "/" + request.pathVariable("org") + "/" + request.pathVariable("env");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", issuer + PorticaPaths.MCP + "/" + request.pathVariable("name"));
        body.put("authorization_servers", List.of(issuer));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "MCP server " + request.pathVariable("name"));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CACHE).bodyValue(body);
    }

    /** Scheme and host the client used, honouring X-Forwarded-Proto/Host from a load balancer. */
    static String baseUrl(ServerHttpRequest request) {
        URI uri = request.getURI();
        String proto = first(request.getHeaders().getFirst("X-Forwarded-Proto"));
        String host = first(request.getHeaders().getFirst("X-Forwarded-Host"));
        String scheme = proto != null ? proto : uri.getScheme();
        String authority = host != null ? host : uri.getRawAuthority();
        return scheme + "://" + authority;
    }

    private static String first(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.split(",")[0].strip();
        return v.matches("[A-Za-z0-9.:\\-\\[\\]]+") ? v : null;
    }
}
