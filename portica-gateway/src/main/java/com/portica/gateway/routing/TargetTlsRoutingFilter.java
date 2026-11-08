package com.portica.gateway.routing;

import com.portica.common.security.Pem;
import com.portica.gateway.runtime.RuntimeConfigRegistry;
import com.portica.gateway.runtime.RuntimeModel.RuntimeTargetTls;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.NettyRoutingFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutual TLS from the gateway to backends. Routes whose target server has TLS settings carry their
 * fingerprint in the route metadata ({@link RouteDefinitionFactory#METADATA_TARGET_TLS}); this routing filter
 * runs just before Spring Cloud Gateway's own {@link NettyRoutingFilter} (which then sees the exchange as
 * routed) and sends those requests with an HTTP client presenting the configured client certificate and
 * trusting the configured CAs. Other routes use the gateway's default client, exactly as before. One client
 * per distinct TLS setting, built on first use.
 */
@Component
public class TargetTlsRoutingFilter extends NettyRoutingFilter {

    private static final Logger log = LoggerFactory.getLogger(TargetTlsRoutingFilter.class);
    private static final int MAX_CLIENTS = 256;

    private final HttpClient base;
    private final RuntimeConfigRegistry registry;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public TargetTlsRoutingFilter(HttpClient httpClient, ObjectProvider<List<HttpHeadersFilter>> headersFilters,
                                  HttpClientProperties properties, RuntimeConfigRegistry registry) {
        super(httpClient, headersFilters, properties);
        this.base = httpClient;
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return super.getOrder() - 1;
    }

    @Override
    protected HttpClient getHttpClient(Route route, ServerWebExchange exchange) {
        Object fingerprint = route.getMetadata().get(RouteDefinitionFactory.METADATA_TARGET_TLS);
        if (fingerprint == null) {
            return super.getHttpClient(route, exchange);
        }
        RuntimeTargetTls tls = registry.targetTls(fingerprint.toString());
        if (tls == null) {
            // the route outlived its configuration: never fall back to an unauthenticated client
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Target TLS settings are not available");
        }
        HttpClient secured = clients.computeIfAbsent(tls.fingerprint(), key -> {
            if (clients.size() >= MAX_CLIENTS) {
                clients.clear();
            }
            return secure(base, tls);
        });
        // the per-route connect timeout, as the default client applies it
        Object connectTimeout = route.getMetadata().get(RouteDefinitionFactory.CONNECT_TIMEOUT);
        return connectTimeout == null ? secured
                : secured.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(connectTimeout.toString()));
    }

    /** The base client with TLS: client certificate and key (if any), trusted CAs (if any), host name check. */
    static HttpClient secure(HttpClient base, RuntimeTargetTls tls) {
        SslContext context = sslContext(tls);
        boolean verifyHostname = tls.verifyHostname();
        return base.secure(spec -> spec.sslContext(context).handlerConfigurator(handler -> {
            SSLEngine engine = handler.engine();
            SSLParameters parameters = engine.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(verifyHostname ? "HTTPS" : null);
            engine.setSSLParameters(parameters);
        }));
    }

    static SslContext sslContext(RuntimeTargetTls tls) {
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (tls.clientCertificate() != null && tls.clientKey() != null) {
                List<X509Certificate> chain = Pem.certificates(tls.clientCertificate());
                builder.keyManager(Pem.privateKey(tls.clientKey()), chain.toArray(X509Certificate[]::new));
            }
            if (tls.trustedCertificates() != null) {
                builder.trustManager(Pem.certificates(tls.trustedCertificates()).toArray(X509Certificate[]::new));
            }
            return builder.build();
        } catch (SSLException | IllegalArgumentException e) {
            log.error("Invalid target TLS settings: {}", e.getMessage());
            throw new IllegalStateException("Invalid target TLS settings", e);
        }
    }
}
