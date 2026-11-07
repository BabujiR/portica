package com.portica.gateway.routing;

import com.portica.gateway.runtime.RuntimeConfigRegistry;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Exposes Portica deployments to Spring Cloud Gateway. It is combined with any routes from
 * properties, so operators can still add static routes (health checks, redirects) in YAML.
 */
@Component
public class PorticaRouteDefinitionLocator implements RouteDefinitionLocator {

    private final RuntimeConfigRegistry registry;

    public PorticaRouteDefinitionLocator(RuntimeConfigRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(registry.routeDefinitions());
    }
}
