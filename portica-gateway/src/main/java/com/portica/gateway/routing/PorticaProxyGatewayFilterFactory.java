package com.portica.gateway.routing;

import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.engine.CompiledProxy;
import com.portica.gateway.policy.engine.PolicyEngine;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Per-route filter for a deployed proxy. It resolves the compiled bundle for the exact revision the
 * route was built for, then hands the exchange to the {@link PolicyEngine}, which runs the flows and
 * maps {@code /{org}/{env}{basePath}/rest} to {@code {targetPath}/rest} on the target.
 */
@Component
public class PorticaProxyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<PorticaProxyGatewayFilterFactory.Config> {

    /** Name used in route definitions (class name minus "GatewayFilterFactory"). */
    public static final String NAME = "PorticaProxy";

    private final RuntimeConfigRegistry registry;
    private final PolicyEngine engine;

    public PorticaProxyGatewayFilterFactory(RuntimeConfigRegistry registry, PolicyEngine engine) {
        super(Config.class);
        this.registry = registry;
        this.engine = engine;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Must run before {@link NettyWriteResponseFilter} (order -1): that filter writes the target's
     * response to the exchange it was given, so only a filter ordered ahead of it can wrap the
     * response for the response flow. Unordered route filters would get order 1 and be too late.
     */
    static final int ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;

    @Override
    public GatewayFilter apply(Config config) {
        return new OrderedGatewayFilter((exchange, chain) -> {
            CompiledProxy proxy = registry.compiledProxy(config.getBundle());
            if (proxy == null) {
                // Route outlived its bundle (undeployed mid-request). Fail closed rather than skip policies.
                PolicyFault fault = PolicyFault.of("ProxyUnavailable", 503, "The API proxy is being redeployed");
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body = fault.defaultBody(exchange.getRequest().getHeaders().getFirst("X-Portica-Request-Id"));
                return exchange.getResponse().writeWith(
                        reactor.core.publisher.Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            }
            return engine.handle(exchange, chain, proxy, config.getPrefix(), config.getTargetPath());
        }, ORDER);
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
        private String bundle;

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

        public String getBundle() {
            return bundle;
        }

        public void setBundle(String bundle) {
            this.bundle = bundle;
        }
    }
}
