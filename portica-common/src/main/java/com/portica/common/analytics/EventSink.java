package com.portica.common.analytics;

import java.util.Map;

/** Where services hand analytics events (Kafka in production, a list in tests, nothing when disabled). */
@FunctionalInterface
public interface EventSink {

    /** Never blocks and never throws: analytics must not affect the call being measured. */
    void publish(String topic, String key, Map<String, Object> event);

    EventSink NONE = (topic, key, event) -> {
    };
}
