package com.portica.common.analytics;

import java.util.List;

/**
 * ClickHouse tables for analytics. The JSON messages on {@link AnalyticsTopics} use the same field
 * names as these columns, so the analytics service inserts them as-is ({@code FORMAT JSONEachRow}).
 * Every statement is idempotent; the analytics service runs them at start-up.
 */
public final class AnalyticsSchema {

    public static final String API_EVENTS = "api_events";
    public static final String TRACE_TRANSACTIONS = "trace_transactions";

    /** Raw API calls, kept 90 days. Sorted for the console's typical filters (org, env, proxy, time). */
    public static final String CREATE_API_EVENTS = """
            CREATE TABLE IF NOT EXISTS api_events (
                event_time      DateTime64(3, 'UTC'),
                request_id      String,
                organization    LowCardinality(String),
                environment     LowCardinality(String),
                api_proxy       LowCardinality(String),
                revision        UInt32,
                method          LowCardinality(String),
                path            String,
                status          UInt16,
                total_ms        UInt32,
                target_ms       UInt32,
                target_called   UInt8,
                request_bytes   UInt64,
                response_bytes  UInt64,
                client_ip       String,
                app             LowCardinality(String),
                developer       String,
                api_product     LowCardinality(String),
                fault           LowCardinality(String),
                cache_hit       UInt8,
                gateway         LowCardinality(String)
            )
            ENGINE = MergeTree
            PARTITION BY toYYYYMMDD(event_time)
            ORDER BY (organization, environment, api_proxy, event_time)
            TTL toDateTime(event_time) + INTERVAL 90 DAY
            """;

    /** Trace transactions (full step-by-step detail as JSON), kept 1 day. */
    public static final String CREATE_TRACE_TRANSACTIONS = """
            CREATE TABLE IF NOT EXISTS trace_transactions (
                session_id      String,
                event_time      DateTime64(3, 'UTC'),
                request_id      String,
                organization    LowCardinality(String),
                environment     LowCardinality(String),
                api_proxy       LowCardinality(String),
                status          UInt16,
                total_ms        UInt32,
                payload         String
            )
            ENGINE = MergeTree
            ORDER BY (session_id, event_time)
            TTL toDateTime(event_time) + INTERVAL 1 DAY
            """;

    public static final List<String> STATEMENTS = List.of(CREATE_API_EVENTS, CREATE_TRACE_TRANSACTIONS);

    private AnalyticsSchema() {
    }
}
