package com.portica.gateway.policy.credentials;

import com.portica.common.security.Hashing;
import com.portica.gateway.runtime.RuntimeModel.RuntimeApiProduct;
import com.portica.gateway.runtime.RuntimeModel.RuntimeCredential;
import com.portica.gateway.runtime.RuntimeModel.RuntimeEnvironmentConfig;
import com.portica.gateway.runtime.RuntimeModel.RuntimeKeyValueMap;
import com.portica.gateway.runtime.RuntimeModel.RuntimeProxy;
import com.portica.gateway.runtime.RuntimeModel.RuntimeQuota;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Snapshot pieces for tests: products, credentials (from plain keys/secrets) and key value maps. */
public final class TestCredentials {

    private TestCredentials() {
    }

    public static RuntimeApiProduct product(String name, List<String> proxies, List<String> paths, List<String> scopes,
                                            RuntimeQuota quota) {
        return new RuntimeApiProduct(name, proxies, paths, scopes, quota, Map.of("tier", name));
    }

    public static RuntimeCredential credential(String key, String secret, String app, Instant expiresAt,
                                               String... products) {
        return new RuntimeCredential("cred-" + app, Hashing.sha256Hex(key), Hashing.sha256Hex(secret), expiresAt,
                "app-" + app, app, Map.of("team", "mobile"), "dev-1", "ada@example.com", List.of(products));
    }

    public static RuntimeEnvironmentConfig environment(long version, List<RuntimeProxy> proxies,
                                                       List<RuntimeApiProduct> products,
                                                       List<RuntimeCredential> credentials,
                                                       List<RuntimeKeyValueMap> maps) {
        return new RuntimeEnvironmentConfig("acme", "dev", version, Instant.now(), proxies, List.of(), products,
                credentials, maps);
    }

    /** A registry holding one acme/dev environment. */
    public static CredentialRegistry registry(List<RuntimeApiProduct> products, List<RuntimeCredential> credentials) {
        CredentialRegistry registry = new CredentialRegistry();
        registry.update(environment(1, List.of(), products, credentials, List.of()));
        return registry;
    }
}
