package com.portica.gateway.policy.credentials;

import java.util.Optional;

/** Looks up app credentials by consumer key. Lookups are in memory (no I/O on the request path). */
public interface CredentialResolver {

    /**
     * The app owning {@code consumerKey} in {@code organization}/{@code environment}, or empty when the key
     * is unknown, revoked, its app is revoked or its developer inactive. Expiry is checked by the caller.
     */
    Optional<AppCredential> resolve(String organization, String environment, String consumerKey);
}
