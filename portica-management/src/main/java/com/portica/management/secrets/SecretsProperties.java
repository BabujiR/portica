package com.portica.management.secrets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Where the keys that protect stored secrets live ({@code portica.secrets.*}).
 * <p>
 * Every secret (encrypted key value map entries, AI provider API keys, MCP server credentials and tokens,
 * target server TLS keys) is encrypted with its own random data key, and that data key is wrapped by a
 * key-encryption key (KEK) held either locally or in HashiCorp Vault's Transit engine.
 *
 * @param provider {@code local} or {@code vault}
 * @param local    local KEKs by id; {@code activeKey} wraps new data keys. Without any, the legacy
 *                 {@code portica.kvm.encryption-key} is used as KEK {@code default}.
 * @param vault    Vault Transit settings
 * @param rotation background re-wrapping of data keys whose KEK is no longer the current one
 */
@ConfigurationProperties(prefix = "portica.secrets")
public record SecretsProperties(@DefaultValue("local") String provider,
                                @DefaultValue Local local,
                                @DefaultValue Vault vault,
                                @DefaultValue Rotation rotation) {

    public record Local(String activeKey, Map<String, String> keys) {

        public Local {
            keys = keys == null ? Map.of() : Map.copyOf(keys);
        }
    }

    /**
     * @param address      e.g. {@code https://vault.internal:8200}
     * @param token        Vault token (or use {@code tokenFile}, e.g. written by the Vault agent)
     * @param tokenFile    file holding the token; re-read when Vault answers 403
     * @param namespace    Vault Enterprise namespace (optional)
     * @param transitMount where the Transit engine is mounted
     * @param keyName      Transit key that wraps Portica's data keys
     * @param cacheSize    unwrapped data keys kept in memory (each unwrap is a Vault call otherwise)
     */
    public record Vault(String address, String token, String tokenFile, String namespace,
                        @DefaultValue("transit") String transitMount,
                        @DefaultValue("portica") String keyName,
                        @DefaultValue("10000") int cacheSize,
                        @DefaultValue("10s") Duration timeout) {
    }

    public record Rotation(@DefaultValue("true") boolean enabled, @DefaultValue("6h") Duration interval) {
    }
}
