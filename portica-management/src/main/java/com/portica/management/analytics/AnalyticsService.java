package com.portica.management.analytics;

import com.portica.common.error.InvalidRequestException;
import com.portica.management.analytics.AnalyticsDtos.Event;
import com.portica.management.analytics.AnalyticsDtos.Events;
import com.portica.management.analytics.AnalyticsDtos.Summary;
import com.portica.management.analytics.AnalyticsDtos.Timeseries;
import com.portica.management.analytics.AnalyticsDtos.TimeseriesPoint;
import com.portica.management.analytics.AnalyticsDtos.Top;
import com.portica.management.analytics.AnalyticsDtos.TopEntry;
import com.portica.management.analytics.AnalyticsDtos.Window;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard queries over {@code api_events}. Every query is scoped to one organization and a time
 * window; values reach ClickHouse only as typed parameters, and grouping columns come from a fixed list.
 */
@Service
public class AnalyticsService {

    static final Duration DEFAULT_WINDOW = Duration.ofHours(1);
    static final Duration MAX_WINDOW = Duration.ofDays(31);

    /** Dimensions for "top N" breakdowns: request name to column expression. */
    static final Map<String, String> DIMENSIONS = Map.of(
            "api_proxy", "api_proxy",
            "environment", "environment",
            "app", "app",
            "api_product", "api_product",
            "developer", "developer",
            "status", "toString(status)",
            "fault", "fault",
            "gateway", "gateway");

    private static final String FILTER = """
            organization = {org:String}
              AND event_time >= fromUnixTimestamp64Milli({from:Int64}, 'UTC')
              AND event_time < fromUnixTimestamp64Milli({to:Int64}, 'UTC')
              AND ({env:String} = '' OR environment = {env:String})
              AND ({api:String} = '' OR api_proxy = {api:String})
              AND ({developer:String} = '' OR developer = {developer:String})
            """;

    private final AnalyticsStore store;

    public AnalyticsService(AnalyticsStore store) {
        this.store = store;
    }

    public Summary summary(String org, Instant from, Instant to, String env, String api) {
        return summary(org, from, to, env, api, null);
    }

    /** {@code developer}: only this developer's traffic (email), e.g. for the developer portal. */
    public Summary summary(String org, Instant from, Instant to, String env, String api, String developer) {
        Window w = window(from, to, env, api);
        List<Map<String, Object>> rows = store.query("""
                SELECT count() AS requests,
                       countIf(status >= 400 AND status < 500) AS errors4xx,
                       countIf(status >= 500) AS errors5xx,
                       ifNotFinite(avg(total_ms), 0) AS avgMs,
                       ifNotFinite(quantile(0.5)(total_ms), 0) AS p50Ms,
                       ifNotFinite(quantile(0.95)(total_ms), 0) AS p95Ms,
                       ifNotFinite(quantile(0.99)(total_ms), 0) AS p99Ms,
                       ifNotFinite(avgIf(target_ms, target_called = 1), 0) AS avgTargetMs,
                       countIf(cache_hit = 1) AS cacheHits
                FROM api_events
                WHERE\s""" + FILTER, params(org, w, developer));
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.getFirst();
        long requests = asLong(r.get("requests"));
        long e4 = asLong(r.get("errors4xx"));
        long e5 = asLong(r.get("errors5xx"));
        double errorRate = requests == 0 ? 0 : round((double) (e4 + e5) / requests);
        return new Summary(w, requests, e4, e5, errorRate, round(asDouble(r.get("avgMs"))), round(asDouble(r.get("p50Ms"))),
                round(asDouble(r.get("p95Ms"))), round(asDouble(r.get("p99Ms"))), round(asDouble(r.get("avgTargetMs"))),
                asLong(r.get("cacheHits")));
    }

    public Timeseries timeseries(String org, Instant from, Instant to, String env, String api) {
        return timeseries(org, from, to, env, api, null);
    }

    public Timeseries timeseries(String org, Instant from, Instant to, String env, String api, String developer) {
        Window w = window(from, to, env, api);
        long bucketMs = bucketSeconds(Duration.between(w.from(), w.to())) * 1000;
        Map<String, Object> params = params(org, w, developer);
        params.put("bucket", bucketMs);
        List<Map<String, Object>> rows = store.query("""
                SELECT intDiv(toUnixTimestamp64Milli(event_time), {bucket:Int64}) * {bucket:Int64} AS t,
                       count() AS requests,
                       countIf(status >= 400) AS errors,
                       ifNotFinite(avg(total_ms), 0) AS avgMs,
                       ifNotFinite(quantile(0.95)(total_ms), 0) AS p95Ms
                FROM api_events
                WHERE\s""" + FILTER + """
                GROUP BY t
                ORDER BY t
                """, params);
        Map<Long, Map<String, Object>> byTime = new HashMap<>();
        rows.forEach(r -> byTime.put(asLong(r.get("t")), r));
        // every bucket in the window, zero-filled, so charts don't draw lines across gaps
        List<TimeseriesPoint> points = new ArrayList<>();
        for (long t = Math.floorDiv(w.from().toEpochMilli(), bucketMs) * bucketMs; t < w.to().toEpochMilli(); t += bucketMs) {
            Map<String, Object> r = byTime.getOrDefault(t, Map.of());
            points.add(new TimeseriesPoint(Instant.ofEpochMilli(t), asLong(r.get("requests")), asLong(r.get("errors")),
                    round(asDouble(r.get("avgMs"))), round(asDouble(r.get("p95Ms")))));
        }
        return new Timeseries(w, bucketMs / 1000, points);
    }

    public Top top(String org, String dimension, Instant from, Instant to, String env, String api, int limit) {
        return top(org, dimension, from, to, env, api, limit, null);
    }

    public Top top(String org, String dimension, Instant from, Instant to, String env, String api, int limit,
                   String developer) {
        String column = DIMENSIONS.get(dimension);
        if (column == null) {
            throw new InvalidRequestException("dimension must be one of " + DIMENSIONS.keySet().stream().sorted().toList());
        }
        Window w = window(from, to, env, api);
        Map<String, Object> params = params(org, w, developer);
        List<Map<String, Object>> rows = store.query("""
                SELECT %s AS name,
                       count() AS requests,
                       countIf(status >= 400) AS errors,
                       ifNotFinite(avg(total_ms), 0) AS avgMs
                FROM api_events
                WHERE %s
                GROUP BY name
                ORDER BY requests DESC, name
                LIMIT %d
                """.formatted(column, FILTER, Math.clamp(limit, 1, 50)), params);
        return new Top(w, dimension, rows.stream().map(r -> new TopEntry(String.valueOf(r.get("name")),
                asLong(r.get("requests")), asLong(r.get("errors")), round(asDouble(r.get("avgMs"))))).toList());
    }

    public Events events(String org, Instant from, Instant to, String env, String api, String statusClass, int limit) {
        Window w = window(from, to, env, api);
        Map<String, Object> params = params(org, w);
        int[] range = statusRange(statusClass);
        params.put("minStatus", range[0]);
        params.put("maxStatus", range[1]);
        List<Map<String, Object>> rows = store.query("""
                SELECT toUnixTimestamp64Milli(event_time) AS t, request_id, environment, api_proxy, revision, method,
                       path, status, total_ms, target_ms, app, developer, api_product, fault, client_ip, gateway
                FROM api_events
                WHERE %s
                  AND status >= {minStatus:UInt16} AND status <= {maxStatus:UInt16}
                ORDER BY event_time DESC
                LIMIT %d
                """.formatted(FILTER, Math.clamp(limit, 1, 500)), params);
        return new Events(w, rows.stream().map(r -> new Event(Instant.ofEpochMilli(asLong(r.get("t"))),
                text(r.get("request_id")), text(r.get("environment")), text(r.get("api_proxy")), asLong(r.get("revision")),
                text(r.get("method")), text(r.get("path")), (int) asLong(r.get("status")), asLong(r.get("total_ms")),
                asLong(r.get("target_ms")), text(r.get("app")), text(r.get("developer")), text(r.get("api_product")),
                text(r.get("fault")), text(r.get("client_ip")), text(r.get("gateway")))).toList());
    }

    // ------------------------------------------------------------------ helpers

    static Window window(Instant from, Instant to, String env, String api) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end)) {
            throw new InvalidRequestException("from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRequestException("The time window can be at most 31 days");
        }
        return new Window(start, end, blankToNull(env), blankToNull(api));
    }

    /** Chart resolution: about 60 to 170 points for any window. */
    static long bucketSeconds(Duration window) {
        long s = window.toSeconds();
        if (s <= 3_600) return 60;
        if (s <= 6 * 3_600) return 300;
        if (s <= 24 * 3_600) return 900;
        if (s <= 7 * 86_400) return 3_600;
        return 21_600;
    }

    static int[] statusRange(String statusClass) {
        if (statusClass == null || statusClass.isBlank()) {
            return new int[]{0, 999};
        }
        return switch (statusClass) {
            case "2xx" -> new int[]{200, 299};
            case "3xx" -> new int[]{300, 399};
            case "4xx" -> new int[]{400, 499};
            case "5xx" -> new int[]{500, 599};
            case "errors" -> new int[]{400, 599};
            default -> throw new InvalidRequestException("status must be 2xx, 3xx, 4xx, 5xx or errors");
        };
    }

    private static Map<String, Object> params(String org, Window w) {
        return params(org, w, null);
    }

    private static Map<String, Object> params(String org, Window w, String developer) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("developer", developer == null ? "" : developer);
        p.put("org", org);
        p.put("from", w.from().toEpochMilli());
        p.put("to", w.to().toEpochMilli());
        p.put("env", w.environment() == null ? "" : w.environment());
        p.put("api", w.apiProxy() == null ? "" : w.apiProxy());
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : o == null ? 0 : Long.parseLong(o.toString());
    }

    static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double round(double d) {
        return Double.isFinite(d) ? Math.round(d * 1000) / 1000.0 : 0;
    }
}
