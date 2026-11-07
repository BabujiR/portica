package com.portica.gateway.policy.credentials;

import java.util.List;
import java.util.Optional;

/** Tries each resolver in order: developer apps first, then development keys. */
public final class CompositeCredentialResolver implements CredentialResolver {

    private final List<CredentialResolver> resolvers;

    public CompositeCredentialResolver(List<CredentialResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    @Override
    public Optional<AppCredential> resolve(String organization, String environment, String consumerKey) {
        for (CredentialResolver r : resolvers) {
            Optional<AppCredential> found = r.resolve(organization, environment, consumerKey);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
