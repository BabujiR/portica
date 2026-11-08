package com.portica.management.analytics;

import com.portica.common.error.InvalidRequestException;
import com.portica.management.analytics.AnalyticsDtos.Window;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Usage and cost of the AI gateway, and MCP tool calls (Phase 7), from ClickHouse. */
@Service
public class AiMcpAnalyticsService {

    static final Map<String, String> AI_DIMENSIONS = Map.of(
            "ai_proxy", "ai_proxy", "model", "model", "model_alias", "model_alias", "provider", "provider",
            "app", "app", "developer", "developer", "environment", "environment", "error", "error");
    static final Map<String, String> MCP_DIMENSIONS = Map.of(
            "tool", "tool", "mcp_server", "mcp_server", "app", "app", "developer", "developer",
            "environment", "environment", "server_type", "server_type");

    private static final String AI_FILTER = """
            organization = {org:String}
              AND event_time >= fromUnixTimestamp64Milli({from:Int64}, 'UTC')
              AND event_time < fromUnixTimestamp64Milli({to:Int64}, 'UTC')
              AND ({env:String} = '' OR environment = {env:String})
              AND ({name:String} = '' OR ai_proxy = {name:String})
            """;
    private static final String MCP_FILTER = AI_FILTER.replace("ai_proxy = {name:String}", "mcp_server = {name:String}");

    public record AiSummary(Window window, long requests, long errors, long promptTokens, long completionTokens,
                            long totalTokens, double costUsd, double avgMs, double p95Ms, double avgFirstTokenMs,
                            long fallbacks, long piiRedactions) {
    }

    public record AiPoint(Instant time, long requests, long errors, long tokens, double costUsd) {
    }

    public record AiTimeseries(Window window, long bucketSeconds, List<AiPoint> points) {
    }

    public record AiTopEntry(String name, long requests, long errors, long tokens, double costUsd, double avgMs) {
    }

    public record AiTop(Window window, String dimension, List<AiTopEntry> entries) {
    }

    public record McpSummary(Window window, long calls, long errors, double errorRate, double avgMs, double p95Ms,
                             long tools, long apps) {
    }

    public record McpTopEntry(String name, long calls, long errors, double avgMs) {
    }

    public record McpTop(Window window, String dimension, List<McpTopEntry> entries) {
    }

    public record McpCall(Instant time, String requestId, String environment, String mcpServer, String tool,
                          String app, boolean error, int upstreamStatus, long totalMs, String message) {
    }

    private final AnalyticsStore store;

    public AiMcpAnalyticsService(AnalyticsStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ AI

    public AiSummary aiSummary(String org, Instant from, Instant to, String env, String aiProxy) {
        Window w = AnalyticsService.window(from, to, env, aiProxy);
        Map<String, Object> r = first(store.query("""
                SELECT count() AS requests, countIf(status >= 400) AS errors,
                       sum(prompt_tokens) AS promptTokens, sum(completion_tokens) AS completionTokens,
                       sum(total_tokens) AS totalTokens, sum(cost_usd) AS costUsd,
                       ifNotFinite(avg(total_ms), 0) AS avgMs, ifNotFinite(quantile(0.95)(total_ms), 0) AS p95Ms,
                       ifNotFinite(avgIf(first_token_ms, streamed = 1), 0) AS avgFirstTokenMs,
                       countIf(attempts > 1) AS fallbacks, sum(pii_redactions) AS piiRedactions
                FROM ai_requests
                WHERE\s""" + AI_FILTER, params(org, w)));
        return new AiSummary(w, l(r, "requests"), l(r, "errors"), l(r, "promptTokens"), l(r, "completionTokens"),
                l(r, "totalTokens"), money(d(r, "costUsd")), round(d(r, "avgMs")), round(d(r, "p95Ms")),
                round(d(r, "avgFirstTokenMs")), l(r, "fallbacks"), l(r, "piiRedactions"));
    }

    public AiTimeseries aiTimeseries(String org, Instant from, Instant to, String env, String aiProxy) {
        Window w = AnalyticsService.window(from, to, env, aiProxy);
        long bucketMs = AnalyticsService.bucketSeconds(Duration.between(w.from(), w.to())) * 1000;
        Map<String, Object> params = params(org, w);
        params.put("bucket", bucketMs);
        List<Map<String, Object>> rows = store.query("""
                SELECT intDiv(toUnixTimestamp64Milli(event_time), {bucket:Int64}) * {bucket:Int64} AS t,
                       count() AS requests, countIf(status >= 400) AS errors,
                       sum(total_tokens) AS tokens, sum(cost_usd) AS costUsd
                FROM ai_requests
                WHERE\s""" + AI_FILTER + """
                GROUP BY t
                ORDER BY t
                """, params);
        Map<Long, Map<String, Object>> byTime = new HashMap<>();
        rows.forEach(r -> byTime.put(l(r, "t"), r));
        List<AiPoint> points = new ArrayList<>();
        for (long t = Math.floorDiv(w.from().toEpochMilli(), bucketMs) * bucketMs; t < w.to().toEpochMilli(); t += bucketMs) {
            Map<String, Object> r = byTime.getOrDefault(t, Map.of());
            points.add(new AiPoint(Instant.ofEpochMilli(t), l(r, "requests"), l(r, "errors"), l(r, "tokens"), money(d(r, "costUsd"))));
        }
        return new AiTimeseries(w, bucketMs / 1000, points);
    }

    public AiTop aiTop(String org, String dimension, Instant from, Instant to, String env, String aiProxy, int limit) {
        String column = AI_DIMENSIONS.get(dimension);
        if (column == null) {
            throw new InvalidRequestException("dimension must be one of " + AI_DIMENSIONS.keySet().stream().sorted().toList());
        }
        Window w = AnalyticsService.window(from, to, env, aiProxy);
        List<Map<String, Object>> rows = store.query("""
                SELECT %s AS name, count() AS requests, countIf(status >= 400) AS errors,
                       sum(total_tokens) AS tokens, sum(cost_usd) AS costUsd, ifNotFinite(avg(total_ms), 0) AS avgMs
                FROM ai_requests
                WHERE %s
                GROUP BY name
                ORDER BY costUsd DESC, requests DESC, name
                LIMIT %d
                """.formatted(column, AI_FILTER, Math.clamp(limit, 1, 50)), params(org, w));
        return new AiTop(w, dimension, rows.stream().map(r -> new AiTopEntry(String.valueOf(r.get("name")),
                l(r, "requests"), l(r, "errors"), l(r, "tokens"), money(d(r, "costUsd")), round(d(r, "avgMs")))).toList());
    }

    // ------------------------------------------------------------------ MCP

    public McpSummary mcpSummary(String org, Instant from, Instant to, String env, String server) {
        Window w = AnalyticsService.window(from, to, env, server);
        Map<String, Object> r = first(store.query("""
                SELECT count() AS calls, countIf(is_error = 1) AS errors,
                       ifNotFinite(avg(total_ms), 0) AS avgMs, ifNotFinite(quantile(0.95)(total_ms), 0) AS p95Ms,
                       uniqExact(tool) AS tools, uniqExact(app) AS apps
                FROM mcp_tool_calls
                WHERE\s""" + MCP_FILTER, params(org, w)));
        long calls = l(r, "calls");
        long errors = l(r, "errors");
        return new McpSummary(w, calls, errors, calls == 0 ? 0 : round((double) errors / calls), round(d(r, "avgMs")),
                round(d(r, "p95Ms")), l(r, "tools"), l(r, "apps"));
    }

    public McpTop mcpTop(String org, String dimension, Instant from, Instant to, String env, String server, int limit) {
        String column = MCP_DIMENSIONS.get(dimension);
        if (column == null) {
            throw new InvalidRequestException("dimension must be one of " + MCP_DIMENSIONS.keySet().stream().sorted().toList());
        }
        Window w = AnalyticsService.window(from, to, env, server);
        List<Map<String, Object>> rows = store.query("""
                SELECT %s AS name, count() AS calls, countIf(is_error = 1) AS errors,
                       ifNotFinite(avg(total_ms), 0) AS avgMs
                FROM mcp_tool_calls
                WHERE %s
                GROUP BY name
                ORDER BY calls DESC, name
                LIMIT %d
                """.formatted(column, MCP_FILTER, Math.clamp(limit, 1, 50)), params(org, w));
        return new McpTop(w, dimension, rows.stream().map(r -> new McpTopEntry(String.valueOf(r.get("name")),
                l(r, "calls"), l(r, "errors"), round(d(r, "avgMs")))).toList());
    }

    public List<McpCall> mcpCalls(String org, Instant from, Instant to, String env, String server, int limit) {
        Window w = AnalyticsService.window(from, to, env, server);
        return store.query("""
                        SELECT toUnixTimestamp64Milli(event_time) AS t, request_id, environment, mcp_server, tool, app,
                               is_error, upstream_status, total_ms, error
                        FROM mcp_tool_calls
                        WHERE %s
                        ORDER BY event_time DESC
                        LIMIT %d
                        """.formatted(MCP_FILTER, Math.clamp(limit, 1, 500)), params(org, w)).stream()
                .map(r -> new McpCall(Instant.ofEpochMilli(l(r, "t")), s(r, "request_id"), s(r, "environment"),
                        s(r, "mcp_server"), s(r, "tool"), s(r, "app"), l(r, "is_error") == 1,
                        (int) l(r, "upstream_status"), l(r, "total_ms"), s(r, "error")))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> params(String org, Window w) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", org);
        p.put("from", w.from().toEpochMilli());
        p.put("to", w.to().toEpochMilli());
        p.put("env", w.environment() == null ? "" : w.environment());
        p.put("name", w.apiProxy() == null ? "" : w.apiProxy());
        return p;
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private static long l(Map<String, Object> r, String k) {
        return AnalyticsService.asLong(r.get(k));
    }

    private static double d(Map<String, Object> r, String k) {
        return AnalyticsService.asDouble(r.get(k));
    }

    private static String s(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? "" : v.toString();
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : 0;
    }

    /** Dollars to a millionth of a cent is plenty, and keeps JSON tidy. */
    private static double money(double v) {
        return Double.isFinite(v) ? Math.round(v * 1_000_000) / 1_000_000.0 : 0;
    }
}
