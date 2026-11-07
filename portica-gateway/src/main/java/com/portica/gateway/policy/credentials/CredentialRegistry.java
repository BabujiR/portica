package com.portica.gateway.policy.credentials;

import com.portica.gateway.runtime.RuntimeModel.EnvironmentKey;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The credential index of every environment this gateway serves, replaced whole on each snapshot. */
@Component
public class CredentialRegistry {

    private final Map<String, CredentialIndex> indexes = new ConcurrentHashMap<>();

    public void update(RuntimeEnvironmentConfig config) {
        indexes.put(config.key(), CredentialIndex.of(config));
    }

    public void remove(String environmentKey) {
        indexes.remove(environmentKey);
    }

    /** Empty index for environments this gateway doesn't serve. */
    public CredentialIndex index(String organization, String environment) {
        return indexes.getOrDefault(EnvironmentKey.of(organization, environment), CredentialIndex.EMPTY);
    }

    public boolean serves(String organization, String environment) {
        return indexes.containsKey(EnvironmentKey.of(organization, environment));
    }
}
