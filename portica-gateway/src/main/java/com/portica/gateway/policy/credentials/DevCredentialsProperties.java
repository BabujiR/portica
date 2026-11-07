package com.portica.gateway.policy.credentials;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Local-development API keys under {@code portica.gateway.dev.api-keys}. Do not use in shared
 * environments: keys live in plain configuration. Replaced by developer apps in Phase 4.
 */
@ConfigurationProperties(prefix = "portica.gateway.dev")
public record DevCredentialsProperties(List<DevApiKey> apiKeys) {

    public DevCredentialsProperties {
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }

    public record DevApiKey(String organization, String key, String app, String developer, List<String> products) {
    }
}
