package com.portica.gateway.policy.credentials;

import reactor.core.publisher.Mono;

/**
 * Looks up API keys. Phase 3 ships {@link ConfiguredCredentialResolver} (keys from gateway config,
 * for local development); Phase 4 adds a resolver backed by developer apps in the management plane.
 */
public interface CredentialResolver {

    /** The app owning {@code apiKey} in {@code organization}, or empty when the key is unknown or revoked. */
    Mono<AppCredential> resolveApiKey(String organization, String apiKey);
}
