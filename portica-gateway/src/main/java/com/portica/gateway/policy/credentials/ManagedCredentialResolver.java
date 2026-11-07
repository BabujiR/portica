package com.portica.gateway.policy.credentials;

import java.util.Optional;

/** Credentials of developer apps, from the management plane's environment snapshots. */
public final class ManagedCredentialResolver implements CredentialResolver {

    private final CredentialRegistry registry;

    public ManagedCredentialResolver(CredentialRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<AppCredential> resolve(String organization, String environment, String consumerKey) {
        return registry.index(organization, environment).findByKey(consumerKey);
    }
}
