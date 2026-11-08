package com.portica.gateway.analytics;

import com.portica.common.analytics.AnalyticsTopics;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a finished API call into an analytics event (and, when traced, a trace transaction) and hands
 * it to the {@link EventPublisher}. Called once per request, after the response is complete.
 */
@Component
public class AnalyticsRecorder {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRecorder.class);

    /** Attribute keys on {@link MessageContext#attributes()} set by the engine. */
    public static final String TARGET_STARTED = "portica.target.started";
    public static final String TARGET_RESPONDED = "portica.target.responded";
    public static final String TARGET_PATH = "portica.target.path";

    private final EventPublisher publisher;
    private final AnalyticsProperties properties;

    public AnalyticsRecorder(EventPublisher publisher, AnalyticsProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    public static void targetStarted(MessageContext ctx, String targetPath) {
        ctx.attributes().put(TARGET_STARTED, System.nanoTime());
        ctx.attributes().put(TARGET_PATH, targetPath);
    }

    public static void targetResponded(MessageContext ctx) {
        ctx.attributes().putIfAbsent(TARGET_RESPONDED, System.nanoTime());
    }

    public void complete(ServerWebExchange exchange, MessageContext ctx, Instant startedAt, long startNanos, SignalType signal) {
        try {
            ServerHttpResponse response = exchange.getResponse();
            HttpStatusCode code = response.getStatusCode();
            int status = signal == SignalType.CANCEL ? 499 : code == null ? (signal == SignalType.ON_ERROR ? 500 : 200) : code.value();
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            Long targetStarted = (Long) ctx.attributes().get(TARGET_STARTED);
            Long targetResponded = (Long) ctx.attributes().get(TARGET_RESPONDED);
            long targetMs = targetStarted != null && targetResponded != null ? (targetResponded - targetStarted) / 1_000_000 : 0;

            MessageContext.ProxyInfo p = ctx.proxy();
            ServerHttpRequest request = exchange.getRequest();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_time", startedAt.toString());
            event.put("request_id", ctx.requestId() == null ? "" : ctx.requestId());
            event.put("organization", p.organization());
            event.put("environment", p.environment());
            event.put("api_proxy", p.proxy());
            event.put("revision", p.revision());
            event.put("method", ctx.request().method().name());
            event.put("path", p.basePath() + ctx.request().pathSuffix());
            event.put("status", status);
            event.put("total_ms", totalMs);
            event.put("target_ms", targetMs);
            event.put("target_called", targetStarted != null ? 1 : 0);
            event.put("request_bytes", Math.max(0, request.getHeaders().getContentLength()));
            event.put("response_bytes", Math.max(0, response.getHeaders().getContentLength()));
            event.put("client_ip", text(ctx.variable("client.ip")));
            event.put("app", text(ctx.variable("developer.app.name")));
            event.put("developer", text(ctx.variable("developer.email")));
            event.put("api_product", text(ctx.variable("apiproduct.name")));
            event.put("fault", ctx.fault() == null ? "" : ctx.fault().faultName());
            event.put("cache_hit", "HIT".equals(response.getHeaders().getFirst("X-Portica-Cache")) ? 1 : 0);
            event.put("gateway", properties.gatewayId());
            publisher.publish(AnalyticsTopics.API_EVENTS, ctx.requestId(), event);

            TraceRecorder trace = ctx.trace();
            if (trace != null) {
                if (targetStarted != null) {
                    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                    String base = route == null ? "" : route.getUri().toString();
                    Object path = ctx.attributes().get(TARGET_PATH);
                    trace.target(base + (path == null ? "" : path), targetResponded == null ? status : targetStatus(ctx, status), targetMs);
                }
                publisher.publish(AnalyticsTopics.TRACE_TRANSACTIONS, ctx.requestId(), trace.finish(ctx, status, response.getHeaders()));
            }
        } catch (RuntimeException e) {
            log.debug("Couldn't record analytics for request {}: {}", ctx.requestId(), e.toString());
        }
    }

    /** The target's own status: the response status before policies changed it, when we have it. */
    private static int targetStatus(MessageContext ctx, int fallback) {
        Object s = ctx.attributes().get("portica.target.status");
        return s instanceof Integer i ? i : fallback;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
