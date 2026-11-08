package com.portica.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.stereotype.Component;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * A second, plain-HTTP listener serving the same application, for callers inside the cluster (the MCP
 * service, the developer portal's try-it, health probes) when the main port speaks TLS with client
 * certificates. Enabled by {@code portica.gateway.internal-http-port} (the {@code tls} profile sets 8081);
 * keep it off the public network. Requests on it carry no client certificate.
 */
@Component
@ConditionalOnProperty(name = "portica.gateway.internal-http-port")
public class InternalHttpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InternalHttpServer.class);

    private final HttpHandler handler;
    private final int port;
    private volatile DisposableServer server;

    public InternalHttpServer(HttpHandler handler, @Value("${portica.gateway.internal-http-port}") int port) {
        this.handler = handler;
        this.port = port;
    }

    @Override
    public void start() {
        server = HttpServer.create().port(port).handle(new ReactorHttpHandlerAdapter(handler)).bindNow();
        log.info("Internal plain HTTP listener on port {}", server.port());
    }

    @Override
    public void stop() {
        DisposableServer s = server;
        if (s != null) {
            s.disposeNow(Duration.ofSeconds(20));
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** The bound port (tests use 0). */
    public int port() {
        return server == null ? -1 : server.port();
    }
}
