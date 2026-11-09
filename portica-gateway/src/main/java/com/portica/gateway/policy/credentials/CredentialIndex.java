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

    public static final CredentialIndex EMPTY = new CredentialIndex(Map.of(), Map.of(), Map.of());

    private final Map<String, RuntimeCredential> byKeyHash;
    private final Map<String, RuntimeCredential> byCertificate;
    private final Map<String, ApiProductGrant> products;

    private CredentialIndex(Map<String, RuntimeCredential> byKeyHash, Map<String, RuntimeCredential> byCertificate,
                            Map<String, ApiProductGrant> products) {
        this.byKeyHash = byKeyHash;
        this.byCertificate = byCertificate;
        this.products = products;
    }

    public static CredentialIndex of(RuntimeEnvironmentConfig config) {
        Map<String, ApiProductGrant> products = new HashMap<>();
        for (RuntimeApiProduct p : config.apiProducts()) {
            products.put(p.name(), ApiProductGrant.from(p));
        }
        Map<String, RuntimeCredential> byHash = new HashMap<>();
        Map<String, RuntimeCredential> byCert = new HashMap<>();
        for (RuntimeCredential c : config.credentials()) {
            if (c.keyHash() != null) {
                byHash.put(c.keyHash(), c);
            }
            for (String fingerprint : c.certificateFingerprints()) {
                byCert.put(fingerprint.toLowerCase(java.util.Locale.ROOT), c);
            }
        }
        return new CredentialIndex(Map.copyOf(byHash), Map.copyOf(byCert), Map.copyOf(products));
    }

    public Optional<AppCredential> findByKey(String consumerKey) {
        return findByKeyHash(Hashing.sha256Hex(consumerKey), consumerKey);
    }

    /** {@code clientId} is what the variables expose as the app's client id (the consumer key). */
    public Optional<AppCredential> findByKeyHash(String keyHash, String clientId) {
        return toApp(byKeyHash.get(keyHash), clientId);
    }

    /**
     * The credential a TLS client certificate is registered on, by SHA-256 fingerprint. The app's client id
     * variable is the fingerprint, since the consumer key isn't known.
     */
    public Optional<AppCredential> findByCertificate(String sha256Fingerprint) {
        String fp = sha256Fingerprint.toLowerCase(java.util.Locale.ROOT);
        return toApp(byCertificate.get(fp), fp);
    }

    private Optional<AppCredential> toApp(RuntimeCredential c, String clientId) {
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
