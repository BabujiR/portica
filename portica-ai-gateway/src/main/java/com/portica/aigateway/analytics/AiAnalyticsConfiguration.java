package com.portica.aigateway.analytics;

import com.portica.aigateway.config.AiGatewayProperties;
import com.portica.common.analytics.EventSink;
import com.portica.common.analytics.KafkaEventSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

/** Where AI call events go: Kafka ({@code portica.ai.v1}) unless {@code portica.ai.analytics.enabled=false}. */
@Configuration
public class AiAnalyticsConfiguration {

    @Bean(destroyMethod = "close")
    EventSink aiEventSink(AiGatewayProperties properties) {
        AiGatewayProperties.Analytics a = properties.analytics();
        if (!a.enabled()) {
            return new NoopSink();
        }
        KafkaEventSink sink = new KafkaEventSink(a.bootstrapServers(), "portica-ai-gateway-" + host(), a.queueCapacity());
        sink.start();
        return sink;
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Disabled analytics; closeable so the bean's destroy method always exists. */
    static final class NoopSink implements EventSink, AutoCloseable {
        @Override
        public void publish(String topic, String key, java.util.Map<String, Object> event) {
        }

        @Override
        public void close() {
        }
    }
}
