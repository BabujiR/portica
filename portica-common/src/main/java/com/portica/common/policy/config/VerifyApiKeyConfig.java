package com.portica.common.policy.config;

import java.util.List;

/**
 * VerifyAPIKey: rejects requests without a valid API key.
 *
 * @param apiKey template locating the key; default {@code {request.header.x-api-key}}
 *               (e.g. {@code {request.queryparam.apikey}} for a query parameter)
 */
public record VerifyApiKeyConfig(String apiKey) implements PolicyConfig {

    public static final String DEFAULT_SOURCE = "{request.header.x-api-key}";

    public VerifyApiKeyConfig {
        apiKey = apiKey == null || apiKey.isBlank() ? DEFAULT_SOURCE : apiKey;
    }

    @Override
    public List<String> validate() {
        return new Problems().require(apiKey.contains("{"), "'apiKey' must reference a variable, e.g. "
                + DEFAULT_SOURCE).list();
    }
}
