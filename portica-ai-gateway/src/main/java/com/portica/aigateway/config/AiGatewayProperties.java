package com.portica.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * {@code portica.ai.*}
 *
 * @param managementUrl  portica-management base URL (runtime feed)
 * @param environments   environments to serve ({@code org/env} patterns, {@code *} wildcards)
 * @param internalToken  shared secret the gateway sends; calls without it are refused
 * @param sync           runtime feed polling
 * @param auth           client credentials for the management API
 * @param quotaStore     {@code redis} (shared) or {@code memory} (single instance, tests)
 * @param analytics      Kafka publishing of AI events
 */
@ConfigurationProperties(prefix = "portica.ai")
public record AiGatewayProperties(@DefaultValue("http://localhost:8080") String managementUrl,
                                  @DefaultValue("*") List<String> environments,
                                  @DefaultValue("portica-internal-dev-token") String internalToken,
                                  @DefaultValue Sync sync,
                                  @DefaultValue Auth auth,
                                  @DefaultValue("redis") String quotaStore,
                                  @DefaultValue Analytics analytics) {

    public record Sync(@DefaultValue("true") boolean enabled, @DefaultValue("5s") Duration pollInterval,
                       @DefaultValue("10s") Duration timeout) {
    }

    public record Auth(String tokenUri, String clientId, String clientSecret) {
    }

    public record Analytics(@DefaultValue("true") boolean enabled, @DefaultValue("localhost:9092") String bootstrapServers,
                            @DefaultValue("10000") int queueCapacity) {
    }

    /** True when {@code org/env} matches one of {@link #environments()}. */
    public boolean serves(String organization, String environment) {
        String key = organization + "/" + environment;
        for (String pattern : environments) {
            String regex = java.util.regex.Pattern.quote(pattern.strip()).replace("*", "\\E.*\\Q");
            if (pattern.strip().equals("*") || key.matches(regex)) {
                return true;
            }
        }
        return false;
    }
}
