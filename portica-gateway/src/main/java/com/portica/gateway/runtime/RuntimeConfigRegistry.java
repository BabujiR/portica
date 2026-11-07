package com.portica.gateway.runtime;

import com.portica.gateway.routing.RouteDefinitionFactory;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The gateway's in-memory view of what is deployed. Every change rebuilds the route list and
 * publishes a {@link RefreshRoutesEvent}; Spring Cloud Gateway then swaps its route table
 * atomically while in-flight requests finish on the old routes. No restart involved.
 */
@Component
public class RuntimeConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigRegistry.class);

    private final ApplicationEventPublisher events;
    private final Map<String, RuntimeEnvironmentConfig> environments = new TreeMap<>();
    private volatile List<RouteDefinition> routes = List.of();

    public RuntimeConfigRegistry(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Adds or replaces one environment's configuration. */
    public void apply(RuntimeEnvironmentConfig config) {
        synchronized (environments) {
            RuntimeEnvironmentConfig previous = environments.put(config.key(), config);
            if (previous != null && previous.configVersion() == config.configVersion()) {
                return;
            }
            log.info("Applied {} config version {} ({} proxies)", config.key(), config.configVersion(),
                    config.proxies().size());
            rebuild();
        }
    }

    /** Removes environments that no longer exist or are no longer served by this gateway. */
    public void remove(Collection<String> keys) {
        synchronized (environments) {
            boolean changed = false;
            for (String key : keys) {
                if (environments.remove(key) != null) {
                    log.info("Removed environment {}", key);
                    changed = true;
                }
            }
            if (changed) {
                rebuild();
            }
        }
    }

    /** Known environments and their config versions. */
    public Map<String, Long> versions() {
        synchronized (environments) {
            return environments.values().stream().collect(Collectors.toMap(RuntimeEnvironmentConfig::key,
                    RuntimeEnvironmentConfig::configVersion));
        }
    }

    public List<RouteDefinition> routeDefinitions() {
        return routes;
    }

    public int routeCount() {
        return routes.size();
    }

    private void rebuild() {
        routes = List.copyOf(RouteDefinitionFactory.build(environments.values()));
        events.publishEvent(new RefreshRoutesEvent(this));
    }
}
