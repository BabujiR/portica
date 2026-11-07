package com.portica.gateway.runtime;

import java.util.List;

/**
 * Decides which environments a gateway serves. Patterns: {@code *} (everything),
 * {@code acme/*} (every environment of one organization) or {@code acme/prod} (exactly one).
 * Lets you run, for example, dedicated production gateways separate from non-production ones.
 */
public final class EnvironmentSelector {

    private final List<String> patterns;

    public EnvironmentSelector(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    public boolean matches(String organization, String environment) {
        for (String pattern : patterns) {
            String p = pattern.strip();
            if (p.equals("*")
                    || p.equals(organization + "/*")
                    || p.equals(organization + "/" + environment)) {
                return true;
            }
        }
        return false;
    }
}
