package com.portica.gateway.policy.credentials;

import com.portica.common.security.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;



/**
 * Resolves development API keys listed in {@code portica.gateway.dev.api-keys} (the {@code local}
 * profile). Keys are held only as SHA-256 hashes in memory. A development key opens every proxy of
 * its organization, in every environment, through the products it lists.
 */
public class ConfiguredCredentialResolver implements CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredCredentialResolver.class);

    private final Map<String, AppCredential> byOrgAndHash = new HashMap<>();

    public ConfiguredCredentialResolver(DevCredentialsProperties properties) {
        for (DevCredentialsProperties.DevApiKey k : properties.apiKeys()) {
            if (k.key() == null || k.key().isBlank() || k.organization() == null) {
                continue;
            }
            List<String> products = k.products() == null || k.products().isEmpty() ? List.of("dev") : k.products();
            byOrgAndHash.put(k.organization() + ":" + Hashing.sha256Hex(k.key()),
                    new AppCredential(null, null, null, k.app(), null, k.developer(),
                            products.stream().map(ApiProductGrant::everyProxy).toList(), Map.of(), null, null));
        }
        if (!byOrgAndHash.isEmpty()) {
            log.warn("{} development API key(s) loaded from configuration. Not for shared environments.",
                    byOrgAndHash.size());
        }
    }

    @Override
    public Optional<AppCredential> resolve(String organization, String environment, String consumerKey) {
        AppCredential found = byOrgAndHash.get(organization + ":" + Hashing.sha256Hex(consumerKey));
        if (found == null) {
            return Optional.empty();
        }
        return Optional.of(new AppCredential(consumerKey, null, null, found.appName(), null, found.developerEmail(),
                found.products(), found.attributes(), null, null));
    }

    public boolean isEmpty() {
        return byOrgAndHash.isEmpty();
    }
}
