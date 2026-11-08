package com.portica.management.analytics;

import java.time.Instant;
import java.util.List;

public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** The window and filters a result was computed for. */
    public record Window(Instant from, Instant to, String environment, String apiProxy) {
    }

    public record Summary(Window window, long requests, long errors4xx, long errors5xx, double errorRate,
                          double avgMs, double p50Ms, double p95Ms, double p99Ms, double avgTargetMs,
                          long cacheHits) {
    }

    public record TimeseriesPoint(Instant time, long requests, long errors, double avgMs, double p95Ms) {
    }

    public record Timeseries(Window window, long bucketSeconds, List<TimeseriesPoint> points) {
    }

    public record TopEntry(String name, long requests, long errors, double avgMs) {
    }

    public record Top(Window window, String dimension, List<TopEntry> entries) {
    }

    public record Event(Instant time, String requestId, String environment, String apiProxy, long revision,
                        String method, String path, int status, long totalMs, long targetMs, String app,
                        String developer, String apiProduct, String fault, String clientIp, String gateway) {
    }

    public record Events(Window window, List<Event> events) {
    }
}
