package com.portica.gateway.routing;

import com.portica.common.http.PorticaHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.addOriginalRequestUrl;

/**
 * Per-route filter for a deployed proxy: maps {@code /{org}/{env}{basePath}/rest} to
 * {@code {targetPath}/rest} on the target, and tells the backend which proxy handled the call
 * via {@code X-Portica-*} headers. Query strings are preserved untouched.
 */
@Component
public class PorticaProxyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<PorticaProxyGatewayFilterFactory.Config> {

    /** Name used in route definitions (class name minus "GatewayFilterFactory"). */
    public static final String NAME = "PorticaProxy";

    public PorticaProxyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            addOriginalRequestUrl(exchange, request.getURI());
            String newPath = rewritePath(request.getURI().getRawPath(), config.getPrefix(), config.getTargetPath());
            ServerHttpRequest rewritten = request.mutate()
                    .path(newPath)
                    .headers(headers -> {
                        headers.set(PorticaHeaders.ORGANIZATION, config.getOrganization());
                        headers.set(PorticaHeaders.ENVIRONMENT, config.getEnvironment());
                        headers.set(PorticaHeaders.PROXY, config.getProxy());
                        headers.set(PorticaHeaders.REVISION, config.getRevision());
                    })
                    .build();
            return chain.filter(exchange.mutate().request(rewritten).build());
        };
    }

    /**
     * {@code rewritePath("/acme/dev/pets/1", "/acme/dev/pets", "/v2") -> "/v2/1"}.
     * The route predicate guarantees {@code rawPath} starts with {@code prefix} on a segment boundary.
     */
    static String rewritePath(String rawPath, String prefix, String targetPath) {
        String rest = rawPath.length() > prefix.length() ? rawPath.substring(prefix.length()) : "";
        String path = (targetPath == null ? "" : targetPath) + rest;
        return path.isEmpty() ? "/" : path;
    }

    /** Bound from route definition args. */
    public static class Config {

        private String prefix;
        private String targetPath = "";
        private String organization;
        private String environment;
        private String proxy;
        private String revision;

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public void setTargetPath(String targetPath) {
            this.targetPath = targetPath == null ? "" : targetPath;
        }

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public String getProxy() {
            return proxy;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }
    }
}
