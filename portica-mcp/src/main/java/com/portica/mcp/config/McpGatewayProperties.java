package com.portica.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * {@code portica.mcp.*}
 *
 * @param managementUrl   portica-management base URL (runtime feed, remote server tokens)
 * @param gatewayUrl      API gateway base URL; OpenAPI tool calls go through it
 * @param environments    environments to serve ({@code org/env} patterns, {@code *} wildcards)
 * @param internalToken   shared secret the gateway sends; calls without it are refused
 * @param toolListCache   how long a remote server's tool list is reused
 * @param maxResultBytes  largest tool result passed back to the client (longer ones are cut)
 */
@ConfigurationProperties(prefix = "portica.mcp")
public record McpGatewayProperties(@DefaultValue("http://localhost:8080") String managementUrl,
                                   @DefaultValue("http://localhost:8081") String gatewayUrl,
                                   @DefaultValue("*") List<String> environments,
                                   @DefaultValue("portica-internal-dev-token") String internalToken,
                                   @DefaultValue("60s") Duration toolListCache,
                                   @DefaultValue("1048576") int maxResultBytes,
                                   @DefaultValue Sync sync,
                                   @DefaultValue Auth auth,
                                   @DefaultValue Analytics analytics) {

    public McpGatewayProperties {
        gatewayUrl = gatewayUrl != null && gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
    }

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
            String p = pattern.strip();
            if (p.equals("*") || key.matches(java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q"))) {
                return true;
            }
        }
        return false;
    }
}
