package com.portica.gateway.oauth;

import com.portica.common.http.PorticaPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Maps the OAuth endpoints. WebFlux router functions are consulted before the gateway's own route
 * table, and the management plane refuses API proxies with a base path under {@code /oauth2}.
 */
@Configuration
public class OAuthRoutes {

    @Bean
    RouterFunction<ServerResponse> oauthEndpoints(TokenEndpoint endpoint) {
        return route(POST("/{org}/{env}" + PorticaPaths.TOKEN), endpoint::token)
                .andRoute(POST("/{org}/{env}" + PorticaPaths.REVOKE), endpoint::revoke);
    }
}
