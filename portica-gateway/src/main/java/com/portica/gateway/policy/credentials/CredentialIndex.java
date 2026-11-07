package com.portica.gateway.policy.credentials;

import com.portica.common.security.Hashing;
import com.portica.gateway.runtime.RuntimeModel.RuntimeApiProduct;
import com.portica.gateway.runtime.RuntimeModel.RuntimeCredential;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable lookup of one environment's credentials by key hash, built from its snapshot. */
public final class CredentialIndex {

    public static final CredentialIndex EMPTY = new CredentialIndex(Map.of(), Map.of());

    private final Map<String, RuntimeCredential> byKeyHash;
    private final Map<String, ApiProductGrant> products;

    private CredentialIndex(Map<String, RuntimeCredential> byKeyHash, Map<String, ApiProductGrant> products) {
        this.byKeyHash = byKeyHash;
        this.products = products;
    }

    public static CredentialIndex of(RuntimeEnvironmentConfig config) {
        Map<String, ApiProductGrant> products = new HashMap<>();
        for (RuntimeApiProduct p : config.apiProducts()) {
            products.put(p.name(), ApiProductGrant.from(p));
        }
        Map<String, RuntimeCredential> byHash = new HashMap<>();
        for (RuntimeCredential c : config.credentials()) {
            if (c.keyHash() != null) {
                byHash.put(c.keyHash(), c);
            }
        }
        return new CredentialIndex(Map.copyOf(byHash), Map.copyOf(products));
    }

    public Optional<AppCredential> findByKey(String consumerKey) {
        return findByKeyHash(Hashing.sha256Hex(consumerKey), consumerKey);
    }

    /** {@code clientId} is what the variables expose as the app's client id (the consumer key). */
    public Optional<AppCredential> findByKeyHash(String keyHash, String clientId) {
        RuntimeCredential c = byKeyHash.get(keyHash);
        if (c == null) {
            return Optional.empty();
        }
        List<ApiProductGrant> grants = c.apiProducts().stream().map(products::get).filter(Objects::nonNull).toList();
        return Optional.of(new AppCredential(clientId, c.id(), c.appId(), c.appName(), c.developerId(),
                c.developerEmail(), grants, c.appAttributes(), c.expiresAt(), c.secretHash()));
    }

    public int size() {
        return byKeyHash.size();
    }
}
