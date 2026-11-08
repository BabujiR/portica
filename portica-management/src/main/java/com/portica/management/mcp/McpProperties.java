package com.portica.management.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 7 wiring ({@code portica.*}).
 *
 * @param publicUrl        how browsers reach this management API (OAuth redirect URI base)
 * @param consoleUrl       where to send the admin back after an OAuth sign-in
 * @param gatewayUrl       public gateway URL (advertised in MCP OAuth metadata)
 * @param aiGatewayUrl     where the gateway forwards AI proxy calls (internal network)
 * @param mcpUrl           where the gateway forwards MCP calls (internal network)
 * @param internalToken    shared secret the gateway adds when forwarding to the AI and MCP services
 */
@ConfigurationProperties(prefix = "portica.services")
public record McpProperties(@DefaultValue("http://localhost:8080") String publicUrl,
                            @DefaultValue("http://localhost:5173") String consoleUrl,
                            @DefaultValue("http://localhost:8081") String gatewayUrl,
                            @DefaultValue("http://localhost:8082") String aiGatewayUrl,
                            @DefaultValue("http://localhost:8083") String mcpUrl,
                            @DefaultValue("portica-internal-dev-token") String internalToken) {

    public McpProperties {
        publicUrl = trim(publicUrl);
        consoleUrl = trim(consoleUrl);
        gatewayUrl = trim(gatewayUrl);
        aiGatewayUrl = trim(aiGatewayUrl);
        mcpUrl = trim(mcpUrl);
    }

    private static String trim(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String redirectUri() {
        return publicUrl + "/api/v1/mcp/oauth/callback";
    }
}
