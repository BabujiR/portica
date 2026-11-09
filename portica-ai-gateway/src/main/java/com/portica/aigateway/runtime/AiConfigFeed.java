package com.portica.aigateway.runtime;

import com.portica.aigateway.config.AiGatewayProperties;
import com.portica.common.runtime.ManagementApiClient;
import com.portica.common.runtime.RuntimeFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** AI configuration per environment, polled from the management plane. */
@Component
public class AiConfigFeed implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AiConfigFeed.class);

    private final AiGatewayProperties properties;
    private final RuntimeFeed<AiRuntime.Config> feed;
    private volatile boolean running;

    public AiConfigFeed(AiGatewayProperties properties) {
        this.properties = properties;
        ManagementApiClient client = new ManagementApiClient(properties.managementUrl(), properties.auth().tokenUri(),
                properties.auth().clientId(), properties.auth().clientSecret(), properties.sync().timeout());
        this.feed = new RuntimeFeed<>(client, "ai", AiRuntime.Config.class, properties::serves);
    }

    public Optional<AiRuntime.Config> get(String organization, String environment) {
        return feed.get(organization, environment);
    }

    /** For tests and single-node setups without the management plane. */
    public void put(AiRuntime.Config config) {
        feed.put(config.organization(), config.environment(), config);
    }

    @Override
    public void start() {
        if (properties.sync().enabled()) {
            log.info("Syncing AI configuration from {} every {}", properties.managementUrl(), properties.sync().pollInterval());
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
