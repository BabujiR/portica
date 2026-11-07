package com.portica.gateway.runtime;

import com.portica.gateway.policy.credentials.CredentialRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import com.portica.gateway.policy.engine.CompiledEnvironment;
import com.portica.gateway.policy.engine.CompiledProxy;
import com.portica.gateway.policy.engine.ProxyBundleCompiler;
import com.portica.gateway.routing.RouteDefinitionFactory;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The gateway's in-memory view of what is deployed. Every change compiles the environment's policy
 * bundles, rebuilds the route list and publishes a {@link RefreshRoutesEvent}; Spring Cloud Gateway
 * then swaps its route table atomically while in-flight requests finish on the old routes and their
 * already-resolved compiled bundles. No restart involved.
 */
@Component
public class RuntimeConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigRegistry.class);

    private final ApplicationEventPublisher events;
    private final ProxyBundleCompiler compiler;
    private final CredentialRegistry credentials;
    private final Map<String, RuntimeEnvironmentConfig> environments = new TreeMap<>();
    private final Map<String, CompiledEnvironment> compiledEnvironments = new HashMap<>();
    private volatile List<RouteDefinition> routes = List.of();
    private volatile Map<String, CompiledProxy> compiledProxies = Map.of();

    @Autowired
    public RuntimeConfigRegistry(ApplicationEventPublisher events, ProxyBundleCompiler compiler,
                                 CredentialRegistry credentials) {
        this.events = events;
        this.compiler = compiler;
        this.credentials = credentials;
    }

    /** With a private credential registry (tests). */
    public RuntimeConfigRegistry(ApplicationEventPublisher events, ProxyBundleCompiler compiler) {
        this(events, compiler, new CredentialRegistry());
    }

    /** Adds or replaces one environment's configuration. */
    public void apply(RuntimeEnvironmentConfig config) {
        synchronized (environments) {
            RuntimeEnvironmentConfig previous = environments.get(config.key());
            if (previous != null && previous.configVersion() == config.configVersion()) {
                return;
            }
            CompiledEnvironment compiled = compiler.compile(config);
            // Credentials first: a newly routed proxy must never see an older credential set.
            credentials.update(config);
            environments.put(config.key(), config);
            compiledEnvironments.put(config.key(), compiled);
            log.info("Applied {} config version {} ({} of {} proxies compiled, {} shared flows, {} products, "
                            + "{} credentials, {} key value maps)", config.key(), config.configVersion(),
                    compiled.proxies().size(), config.proxies().size(), config.sharedFlows().size(),
                    config.apiProducts().size(), config.credentials().size(), config.keyValueMaps().size());
            rebuild();
        }
    }

    /** Removes environments that no longer exist or are no longer served by this gateway. */
    public void remove(Collection<String> keys) {
        synchronized (environments) {
            boolean changed = false;
            for (String key : keys) {
                if (environments.remove(key) != null) {
                    compiledEnvironments.remove(key);
                    credentials.remove(key);
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

    /** The compiled bundle for {@code org/env/name@revision}, or null if it is no longer live. */
    public CompiledProxy compiledProxy(String bundleKey) {
        return compiledProxies.get(bundleKey);
    }

    public List<RouteDefinition> routeDefinitions() {
        return routes;
    }

    public int routeCount() {
        return routes.size();
    }

    private void rebuild() {
        Map<String, CompiledProxy> all = new HashMap<>();
        compiledEnvironments.values().forEach(env -> all.putAll(env.proxies()));
        // Publish compiled bundles before routes, so a new route never misses its bundle.
        compiledProxies = Map.copyOf(all);
        routes = List.copyOf(RouteDefinitionFactory.build(environments.values(), all::containsKey));
        events.publishEvent(new RefreshRoutesEvent(this));
    }
}
