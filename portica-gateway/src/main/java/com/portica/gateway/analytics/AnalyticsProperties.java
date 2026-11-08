package com.portica.gateway.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Analytics and trace publishing ({@code portica.gateway.analytics.*}).
 *
 * @param enabled          publish events to Kafka; off means no analytics or traces (requests are unaffected)
 * @param bootstrapServers Kafka brokers, e.g. {@code localhost:9092}
 * @param queueCapacity    events buffered in memory while Kafka is slow; beyond it, new events are dropped
 * @param gatewayId        name of this instance in events (defaults to the host name)
 */
@ConfigurationProperties(prefix = "portica.gateway.analytics")
public record AnalyticsProperties(@DefaultValue("true") boolean enabled, String bootstrapServers, Integer queueCapacity,
                                  String gatewayId) {

    public AnalyticsProperties {
        bootstrapServers = bootstrapServers == null || bootstrapServers.isBlank() ? "localhost:9092" : bootstrapServers;
        queueCapacity = queueCapacity == null || queueCapacity < 100 ? 10_000 : queueCapacity;
        if (gatewayId == null || gatewayId.isBlank()) {
            String host = System.getenv("HOSTNAME");
            if (host == null || host.isBlank()) {
                try {
                    host = java.net.InetAddress.getLocalHost().getHostName();
                } catch (java.net.UnknownHostException e) {
                    host = "gateway";
                }
            }
            gatewayId = host;
        }
    }
}
