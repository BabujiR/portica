package com.portica.gateway.trace;

import com.portica.gateway.config.PorticaGatewayProperties;
import com.portica.gateway.policy.ProxyRequest;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.runtime.ManagementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Active trace sessions, refreshed from the management plane every 2 seconds. Matching is a map
 * lookup per request; when no session is active (the normal case) tracing costs nothing.
 * Each gateway instance counts its own captures, so a session may capture slightly more than
 * {@code maxTransactions} overall when several instances serve the environment.
 */
@Component
public class TraceSessionRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TraceSessionRegistry.class);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private record Active(TraceSession session, AtomicInteger captured) {
    }

    private final ManagementClient management;
    private final PorticaGatewayProperties properties;
    private final Clock clock;
    private volatile Map<String, List<Active>> byProxy = Map.of();
    private volatile Map<String, AtomicInteger> counters = Map.of();
    private Disposable subscription;

    public TraceSessionRegistry(ManagementClient management, PorticaGatewayProperties properties) {
        this.management = management;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /** Replaces the active sessions (from polling, or directly in tests). Capture counts survive. */
    public synchronized void update(Collection<TraceSession> sessions) {
        Map<String, AtomicInteger> nextCounters = new HashMap<>();
        Map<String, List<Active>> next = new HashMap<>();
        for (TraceSession s : sessions) {
            AtomicInteger counter = counters.getOrDefault(s.id(), new AtomicInteger());
            nextCounters.put(s.id(), counter);
            next.computeIfAbsent(key(s.organization(), s.environment(), s.apiProxy()), k -> new java.util.ArrayList<>())
                    .add(new Active(s, counter));
        }
        counters = Map.copyOf(nextCounters);
        byProxy = Map.copyOf(next);
    }

    /** A recorder when an active session wants this request, else null. */
    public TraceRecorder start(MessageContext ctx) {
        Map<String, List<Active>> current = byProxy;
        if (current.isEmpty()) {
            return null;
        }
        MessageContext.ProxyInfo p = ctx.proxy();
        List<Active> candidates = current.get(key(p.organization(), p.environment(), p.proxy()));
        if (candidates == null) {
            return null;
        }
        for (Active a : candidates) {
            TraceSession s = a.session();
            if (s.expiresAt() != null && clock.instant().isAfter(s.expiresAt())) {
                continue;
            }
            if (!matches(s, ctx)) {
                continue;
            }
            if (a.captured().incrementAndGet() > s.maxTransactions()) {
                continue;
            }
            TraceRecorder recorder = new TraceRecorder(s);
            ProxyRequest r = ctx.request();
            recorder.request(r.method().name(), r.uri(), r.headers(), String.valueOf(ctx.variable("client.ip")));
            return recorder;
        }
        return null;
    }

    private static boolean matches(TraceSession s, MessageContext ctx) {
        if (s.filterHeader() != null && !s.filterHeader().isBlank()) {
            String actual = ctx.request().headers().getFirst(s.filterHeader());
            if (actual == null || (s.filterValue() != null && !s.filterValue().isBlank() && !s.filterValue().equals(actual))) {
                return false;
            }
        }
        if (s.filterClientIp() != null && !s.filterClientIp().isBlank()) {
            return s.filterClientIp().equals(String.valueOf(ctx.variable("client.ip")));
        }
        return true;
    }

    private static String key(String org, String env, String proxy) {
        return org + "/" + env + "/" + proxy;
    }

    @Override
    public void start() {
        if (!properties.sync().enabled()) {
            return;
        }
        subscription = Flux.interval(POLL_INTERVAL, POLL_INTERVAL, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(tick -> management.traceSessions()
                        .doOnNext(this::update)
                        .onErrorResume(e -> {
                            log.debug("Couldn't refresh trace sessions: {}", e.toString());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null;
    }
}
