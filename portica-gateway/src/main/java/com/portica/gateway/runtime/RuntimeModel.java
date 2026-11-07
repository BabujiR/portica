package com.portica.gateway.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Gateway-side copy of the management plane's runtime contract
 * ({@code com.portica.management.runtime.RuntimeDtos}). Unknown fields are ignored, so the
 * management plane can add fields without breaking older gateways.
 */
public final class RuntimeModel {

    private RuntimeModel() {
    }

    public record RuntimeIndex(Instant generatedAt, List<EnvironmentVersion> environments) {

        public RuntimeIndex {
            environments = environments == null ? List.of() : List.copyOf(environments);
        }
    }

    public record EnvironmentVersion(String organization, String environment, long configVersion) {

        public String key() {
            return EnvironmentKey.of(organization, environment);
        }
    }

    public record RuntimeEnvironmentConfig(String organization, String environment, long configVersion,
                                           Instant generatedAt, List<RuntimeProxy> proxies) {

        public RuntimeEnvironmentConfig {
            proxies = proxies == null ? List.of() : List.copyOf(proxies);
        }

        public String key() {
            return EnvironmentKey.of(organization, environment);
        }
    }

    public record RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                               int connectTimeoutMs, int readTimeoutMs) {
    }

    /** {@code org/env}, the identity of an environment across the system. */
    public static final class EnvironmentKey {

        private EnvironmentKey() {
        }

        public static String of(String organization, String environment) {
            return organization + "/" + environment;
        }
    }
}
