package com.portica.mcp.runtime;

import com.portica.common.runtime.ManagementApiClient;
import com.portica.common.runtime.RuntimeFeed;
import com.portica.mcp.config.McpGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** MCP server definitions per environment, polled from the management plane. */
@Component
public class McpConfigFeed implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpConfigFeed.class);

    private final McpGatewayProperties properties;
    private final RuntimeFeed<McpRuntime.Config> feed;
    private volatile boolean running;

    public McpConfigFeed(McpGatewayProperties properties, ManagementApiClient client) {
        this.properties = properties;
        this.feed = new RuntimeFeed<>(client, "mcp", McpRuntime.Config.class, properties::serves);
    }

    public Optional<McpRuntime.Config> get(String organization, String environment) {
        return feed.get(organization, environment);
    }

    /** For tests and setups without the management plane. */
    public void put(McpRuntime.Config config) {
        feed.put(config.organization(), config.environment(), config);
    }

    @Override
    public void start() {
        if (properties.sync().enabled()) {
            log.info("Syncing MCP servers from {} every {}", properties.managementUrl(), properties.sync().pollInterval());
            feed.start(properties.sync().pollInterval());
        }
        running = true;
    }

    @Override
    public void stop() {
        feed.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
