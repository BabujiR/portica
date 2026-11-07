package com.portica.gateway.routing;

import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns runtime snapshots into Spring Cloud Gateway route definitions.
 * <p>
 * Each deployed proxy becomes one route matching {@code /{org}/{env}{basePath}/**}, sent to the
 * proxy's target with the {@code PorticaProxy} filter rewriting the path. Longer base paths get
 * higher priority, so {@code /orders/admin} wins over {@code /orders}.
 */
public final class RouteDefinitionFactory {

    /** Route metadata keys understood by Spring Cloud Gateway's HTTP client (milliseconds). */
    static final String CONNECT_TIMEOUT = "connect-timeout";
    static final String RESPONSE_TIMEOUT = "response-timeout";

    public static final String METADATA_ORGANIZATION = "portica.organization";
    public static final String METADATA_ENVIRONMENT = "portica.environment";
    public static final String METADATA_PROXY = "portica.proxy";
    public static final String METADATA_REVISION = "portica.revision";

    private RouteDefinitionFactory() {
    }

    public static List<RouteDefinition> build(Collection<RuntimeEnvironmentConfig> environments) {
        return build(environments, key -> true);
    }

    /** Routes for the proxies whose bundle key passes {@code include} (e.g. only those that compiled). */
    public static List<RouteDefinition> build(Collection<RuntimeEnvironmentConfig> environments,
                                              Predicate<String> include) {
        List<RouteDefinition> routes = new ArrayList<>();
        for (RuntimeEnvironmentConfig env : environments) {
            for (RuntimeProxy proxy : env.proxies()) {
                if (include.test(proxy.bundleKey(env.organization(), env.environment()))) {
                    routes.add(route(env.organization(), env.environment(), proxy));
                }
            }
        }
        return routes;
    }

    static RouteDefinition route(String org, String env, RuntimeProxy proxy) {
        String prefix = gatewayPrefix(org, env, proxy.basePath());

        RouteDefinition route = new RouteDefinition();
        route.setId("portica_" + org + "_" + env + "_" + proxy.name());
        route.setUri(URI.create(proxy.targetUri()));
        route.setOrder(-prefix.length());
        route.setPredicates(List.of(new PredicateDefinition("Path=" + prefix + "/**")));

        FilterDefinition filter = new FilterDefinition();
        filter.setName(PorticaProxyGatewayFilterFactory.NAME);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("prefix", prefix);
        args.put("targetPath", proxy.targetPath() == null ? "" : proxy.targetPath());
        args.put("organization", org);
        args.put("environment", env);
        args.put("proxy", proxy.name());
        args.put("revision", Integer.toString(proxy.revision()));
        args.put("bundle", proxy.bundleKey(org, env));
        filter.setArgs(args);
        route.setFilters(List.of(filter));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(CONNECT_TIMEOUT, proxy.connectTimeoutMs());
        metadata.put(RESPONSE_TIMEOUT, (long) proxy.readTimeoutMs());
        metadata.put(METADATA_ORGANIZATION, org);
        metadata.put(METADATA_ENVIRONMENT, env);
        metadata.put(METADATA_PROXY, proxy.name());
        metadata.put(METADATA_REVISION, proxy.revision());
        route.setMetadata(metadata);
        return route;
    }

    /** {@code /{org}/{env}{basePath}}, the public path prefix of a deployed proxy. */
    static String gatewayPrefix(String org, String env, String basePath) {
        return "/" + org + "/" + env + basePath;
    }
}
