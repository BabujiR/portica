package com.portica.gateway.analytics;

import java.util.Map;

/**
 * Sends analytics events and trace transactions off the request path. Implementations must never
 * block or throw: losing an event is acceptable, slowing down an API call is not.
 */
public interface EventPublisher {

    void publish(String topic, String key, Map<String, Object> event);
}
