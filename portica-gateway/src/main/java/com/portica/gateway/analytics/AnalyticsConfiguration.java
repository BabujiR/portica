package com.portica.gateway.analytics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyticsConfiguration {

    /** Kafka when analytics are enabled, otherwise a publisher that discards everything. */
    @Bean
    EventPublisher eventPublisher(AnalyticsProperties properties) {
        return properties.enabled() ? new KafkaEventPublisher(properties) : (topic, key, event) -> { };
    }
}
