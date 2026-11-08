package com.portica.common.analytics;

/** Kafka topics between the gateways and the analytics service. Messages are JSON; keys are request ids. */
public final class AnalyticsTopics {

    /** One message per API call ({@link AnalyticsSchema#API_EVENTS} row). */
    public static final String API_EVENTS = "portica.analytics.v1";

    /** One message per traced API call ({@link AnalyticsSchema#TRACE_TRANSACTIONS} row). */
    public static final String TRACE_TRANSACTIONS = "portica.trace.v1";

    private AnalyticsTopics() {
    }
}
