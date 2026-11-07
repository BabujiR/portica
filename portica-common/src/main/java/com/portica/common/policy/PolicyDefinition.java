package com.portica.common.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A configured policy inside an API proxy or shared flow revision.
 *
 * @param name             unique within the bundle; steps refer to it by this name
 * @param type             a {@link PolicyType} name, e.g. {@code VerifyAPIKey}
 * @param enabled          disabled policies are skipped wherever they are attached (default true)
 * @param continueOnError  when true a fault from this policy is recorded in variables
 *                         ({@code <name>.failed}) and the flow continues (default false)
 * @param config           type-specific settings, see {@code com.portica.common.policy.config}
 */
public record PolicyDefinition(String name, String type, Boolean enabled, Boolean continueOnError,
                               Map<String, Object> config) {

    public PolicyDefinition {
        // Tolerates null values: the validator reports problems with readable messages.
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public boolean continueOnErrorOrDefault() {
        return continueOnError != null && continueOnError;
    }
}
