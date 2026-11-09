package com.portica.common.analytics;

/** Kafka topics between the gateways and the analytics service. Messages are JSON; keys are request ids. */
public final class AnalyticsTopics {

    /** One message per API call ({@link AnalyticsSchema#API_EVENTS} row). */
    public static final String API_EVENTS = "portica.analytics.v1";

    /** One message per traced API call ({@link AnalyticsSchema#TRACE_TRANSACTIONS} row). */
    public static final String TRACE_TRANSACTIONS = "portica.trace.v1";

    /** One message per LLM call through the AI gateway: tokens, cost, model and provider (Phase 7). */
    public static final String AI_REQUESTS = "portica.ai.v1";

    /** One message per MCP tool call (Phase 7). */
    public static final String MCP_TOOL_CALLS = "portica.mcp.v1";

    private AnalyticsTopics() {
    }
}
