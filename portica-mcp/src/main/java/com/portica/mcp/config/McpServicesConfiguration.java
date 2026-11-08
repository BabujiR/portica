package com.portica.mcp.config;

import com.portica.common.analytics.EventSink;
import com.portica.common.analytics.KafkaEventSink;
import com.portica.common.runtime.ManagementApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Configuration
public class McpServicesConfiguration {

    @Bean
    ManagementApiClient managementApiClient(McpGatewayProperties properties) {
        McpGatewayProperties.Auth auth = properties.auth();
        return new ManagementApiClient(properties.managementUrl(), auth.tokenUri(), auth.clientId(), auth.clientSecret(),
                properties.sync().timeout());
    }

    /** Outbound HTTP (gateway and remote MCP servers); redirects are not followed so credentials stay put. */
    @Bean
    HttpClient mcpHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Tool call events go to Kafka ({@code portica.mcp.v1}) unless {@code portica.mcp.analytics.enabled=false}. */
    @Bean(destroyMethod = "close")
    EventSink mcpEventSink(McpGatewayProperties properties) {
        McpGatewayProperties.Analytics a = properties.analytics();
        if (!a.enabled()) {
            return new ClosableNone();
        }
        KafkaEventSink sink = new KafkaEventSink(a.bootstrapServers(), "portica-mcp-" + host(), a.queueCapacity());
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

    static final class ClosableNone implements EventSink, AutoCloseable {
        @Override
        public void publish(String topic, String key, Map<String, Object> event) {
        }

        @Override
        public void close() {
        }
    }
}
