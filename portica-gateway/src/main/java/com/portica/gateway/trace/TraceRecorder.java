package com.portica.gateway.trace;

import com.portica.gateway.policy.MessageContext;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collects what happened to one traced request: each flow step (ran, skipped, faulted), the target
 * call and the final response. Credentials are masked: authorization headers, cookies, API keys,
 * {@code private.*} variables and client ids never leave the gateway in a trace.
 */
public final class TraceRecorder {

    public static final String MASK = "*****";
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "set-cookie", "x-api-key", "apikey");

    private final TraceSession session;
    private final Instant startedAt = Instant.now();
    private final long startNanos = System.nanoTime();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private Map<String, Object> request;
    private Map<String, Object> target;

    public TraceRecorder(TraceSession session) {
        this.session = session;
    }

    public TraceSession session() {
        return session;
    }

    public void request(String method, String uri, HttpHeaders headers, String clientIp) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", method);
        r.put("uri", maskUri(uri));
        r.put("headers", headers(headers));
        r.put("clientIp", clientIp);
        this.request = r;
    }

    /**
     * @param outcome {@code executed}, {@code skipped} (condition false), {@code fault} or
     *                {@code continued} (faulted with continueOnError)
     */
    public synchronized void step(String flow, String policy, String type, String condition, String outcome,
                                  long durationNanos, String fault) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("flow", flow);
        s.put("policy", policy);
        s.put("type", type);
        if (condition != null) s.put("condition", condition);
        s.put("outcome", outcome);
        s.put("durationMs", Math.round(durationNanos / 10_000.0) / 100.0);
        s.put("atMs", Math.round((System.nanoTime() - startNanos) / 10_000.0) / 100.0);
        if (fault != null) s.put("fault", fault);
        steps.add(s);
    }

    public void target(String url, int status, long durationMs) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("url", url);
        t.put("status", status);
        t.put("durationMs", durationMs);
        this.target = t;
    }

    /** The trace message for {@code trace_transactions}. */
    public synchronized Map<String, Object> finish(MessageContext ctx, int status, HttpHeaders responseHeaders) {
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", ctx.requestId());
        payload.put("startedAt", startedAt.toString());
        payload.put("totalMs", totalMs);
        payload.put("request", request);
        payload.put("steps", steps);
        payload.put("target", target);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("headers", headers(responseHeaders));
        payload.put("response", response);
        payload.put("fault", ctx.fault() == null ? null : Map.of("name", ctx.fault().faultName(),
                "status", ctx.fault().status(), "message", String.valueOf(ctx.fault().getMessage())));
        payload.put("variables", variables(ctx.variables()));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", session.id());
        row.put("event_time", startedAt.toString());
        row.put("request_id", ctx.requestId() == null ? "" : ctx.requestId());
        row.put("organization", session.organization());
        row.put("environment", session.environment());
        row.put("api_proxy", session.apiProxy());
        row.put("status", status);
        row.put("total_ms", totalMs);
        row.put("payload", payload);
        return row;
    }

    private static final java.util.regex.Pattern SECRET_QUERY = java.util.regex.Pattern.compile(
            "(?i)([?&](?:apikey|api_key|key|access_token|token|client_secret|password)=)[^&#]*");

    /** The URI with credential-like query parameters masked. */
    static String maskUri(String uri) {
        return uri == null ? null : SECRET_QUERY.matcher(uri).replaceAll("$1" + MASK);
    }

    static Map<String, Object> headers(HttpHeaders headers) {
        Map<String, Object> out = new TreeMap<>();
        if (headers == null) {
            return out;
        }
        headers.forEach((name, values) -> out.put(name, SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                ? MASK : String.join(", ", values)));
        return out;
    }

    static Map<String, Object> variables(Map<String, Object> variables) {
        Map<String, Object> out = new TreeMap<>();
        variables.forEach((name, value) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean secret = lower.startsWith("private.") || lower.endsWith("client_id") || lower.contains("secret")
                    || lower.contains("token") || lower.contains("password");
            Object safe = secret ? MASK : (value instanceof Number || value instanceof Boolean ? value : truncate(String.valueOf(value)));
            out.put(name, safe);
        });
        return out;
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
