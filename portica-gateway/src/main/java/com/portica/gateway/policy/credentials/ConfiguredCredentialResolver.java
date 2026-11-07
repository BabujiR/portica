package com.portica.gateway.policy.credentials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Resolves API keys listed in {@code portica.gateway.dev.api-keys}. Keys are held only as SHA-256
 * hashes in memory, and lookups compare hashes, so the comparison doesn't leak timing about the key.
 */
@Component
public class ConfiguredCredentialResolver implements CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredCredentialResolver.class);

    private final Map<String, AppCredential> byOrgAndHash = new HashMap<>();

    public ConfiguredCredentialResolver(DevCredentialsProperties properties) {
        for (DevCredentialsProperties.DevApiKey k : properties.apiKeys()) {
            if (k.key() == null || k.key().isBlank() || k.organization() == null) {
                continue;
            }
            byOrgAndHash.put(k.organization() + ":" + sha256(k.key()),
                    new AppCredential(k.key(), k.app(), k.developer(), k.products(), Map.of()));
        }
        if (!byOrgAndHash.isEmpty()) {
            log.warn("{} development API key(s) loaded from configuration. Not for shared environments.",
                    byOrgAndHash.size());
        }
    }

    @Override
    public Mono<AppCredential> resolveApiKey(String organization, String apiKey) {
        return Mono.justOrEmpty(byOrgAndHash.get(organization + ":" + sha256(apiKey)));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
