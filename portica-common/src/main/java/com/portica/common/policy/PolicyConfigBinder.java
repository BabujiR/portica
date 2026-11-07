package com.portica.common.policy;

import com.portica.common.policy.config.PolicyConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link PolicyDefinition}'s free-form {@code config} map into its typed, validated config record.
 * Unknown fields are errors, so typos ({@code "cout": 10}) don't silently fall back to defaults.
 */
public final class PolicyConfigBinder {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private PolicyConfigBinder() {
    }

    /** Binds and validates; throws {@link PolicyConfigException} listing every problem. */
    public static PolicyConfig bind(PolicyDefinition definition) {
        String name = definition.name() == null ? "?" : definition.name();
        PolicyType type = PolicyType.fromTypeName(definition.type())
                .orElseThrow(() -> new PolicyConfigException(name, List.of("unknown policy type '" + definition.type() + "'")));
        PolicyConfig config;
        try {
            config = MAPPER.convertValue(definition.config(), type.configClass());
        } catch (RuntimeException e) { // JacksonException, or a constructor rejecting a value
            throw new PolicyConfigException(name, List.of("invalid config: " + describe(e)));
        }
        List<String> problems = new ArrayList<>(config.validate());
        if (!problems.isEmpty()) {
            throw new PolicyConfigException(name, problems);
        }
        return config;
    }

    private static String describe(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t instanceof JacksonException je ? je.getOriginalMessage() : t.getMessage();
        if (message == null) {
            return t.getClass().getSimpleName();
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
