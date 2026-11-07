package com.portica.gateway.policy.credentials;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The app behind a verified credential.
 *
 * @param clientId     the consumer key as presented by the client
 * @param credentialId id of the credential in the management plane (null for development keys)
 * @param products     approved API products offered in the environment
 * @param secretHash   SHA-256 of the consumer secret (null for development keys)
 * @param expiresAt    null when the credential doesn't expire
 */
public record AppCredential(String clientId, String credentialId, String appId, String appName, String developerId,
                            String developerEmail, List<ApiProductGrant> products, Map<String, String> attributes,
                            Instant expiresAt, String secretHash) {

    public AppCredential {
        products = products == null ? List.of() : List.copyOf(products);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** First product granting {@code proxy} at {@code pathSuffix}. */
    public Optional<ApiProductGrant> productFor(String proxy, String pathSuffix) {
        return products.stream().filter(p -> p.allows(proxy, pathSuffix)).findFirst();
    }

    /** Every scope the app may request (union of its products' scopes). */
    public Set<String> scopes() {
        Set<String> all = new LinkedHashSet<>();
        products.forEach(p -> all.addAll(p.scopes()));
        return all;
    }

    public List<String> productNames() {
        return products.stream().map(ApiProductGrant::name).toList();
    }
}
