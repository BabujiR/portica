package com.portica.gateway.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Gateway-side copy of the management plane's runtime contract
 * ({@code com.portica.management.runtime.RuntimeDtos}). Unknown fields are ignored, so the
 * management plane can add fields without breaking older gateways. Policy bundles use the shared
 * model from portica-common.
 */
public final class RuntimeModel {

    private RuntimeModel() {
    }

    /** Immutable copy without null keys or values (JSON may carry them). */
    static Map<String, String> copy(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(k, v);
            }
        });
        return java.util.Collections.unmodifiableMap(result);
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
                                           Instant generatedAt, List<RuntimeProxy> proxies,
                                           List<RuntimeSharedFlow> sharedFlows, List<RuntimeApiProduct> apiProducts,
                                           List<RuntimeCredential> credentials,
                                           List<RuntimeKeyValueMap> keyValueMaps) {

        @JsonCreator // canonical constructor for JSON; the shorter ones are for tests
        public RuntimeEnvironmentConfig {
            proxies = proxies == null ? List.of() : List.copyOf(proxies);
            sharedFlows = sharedFlows == null ? List.of() : List.copyOf(sharedFlows);
            apiProducts = apiProducts == null ? List.of() : List.copyOf(apiProducts);
            credentials = credentials == null ? List.of() : List.copyOf(credentials);
            keyValueMaps = keyValueMaps == null ? List.of() : List.copyOf(keyValueMaps);
        }

        /** Without products, credentials or key value maps (tests, Phase 3 style). */
        public RuntimeEnvironmentConfig(String organization, String environment, long configVersion,
                                        Instant generatedAt, List<RuntimeProxy> proxies,
                                        List<RuntimeSharedFlow> sharedFlows) {
            this(organization, environment, configVersion, generatedAt, proxies, sharedFlows, List.of(), List.of(),
                    List.of());
        }

        /** Without shared flows (tests, Phase 2 style). */
        public RuntimeEnvironmentConfig(String organization, String environment, long configVersion,
                                        Instant generatedAt, List<RuntimeProxy> proxies) {
            this(organization, environment, configVersion, generatedAt, proxies, List.of());
        }

        public String key() {
            return EnvironmentKey.of(organization, environment);
        }
    }

    /**
     * A deployed proxy. {@code targetTls}: client certificate and trusted CAs for mutual TLS to the target
     * (target servers only), or null for the gateway's default TLS client.
     */
    public record RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                               int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies,
                               ProxyFlows flows, RuntimeTargetTls targetTls) {

        @JsonCreator
        public RuntimeProxy {
            policies = policies == null ? List.of() : List.copyOf(policies);
            flows = flows == null ? ProxyFlows.EMPTY : flows;
        }

        public RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                            int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies, ProxyFlows flows) {
            this(name, revision, basePath, targetUri, targetPath, connectTimeoutMs, readTimeoutMs, policies, flows, null);
        }

        /** Without policies (tests, Phase 2 style). */
        public RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                            int connectTimeoutMs, int readTimeoutMs) {
            this(name, revision, basePath, targetUri, targetPath, connectTimeoutMs, readTimeoutMs, List.of(),
                    ProxyFlows.EMPTY);
        }

        /** Identifies this exact deployment: {@code org/env/name@revision}. */
        public String bundleKey(String organization, String environment) {
            return EnvironmentKey.of(organization, environment) + "/" + name + "@" + revision;
        }
    }

    public record RuntimeSharedFlow(String name, int revision, List<PolicyDefinition> policies, List<FlowStep> steps) {

        public RuntimeSharedFlow {
            policies = policies == null ? List.of() : List.copyOf(policies);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** An API product offered in the environment. Empty {@code paths} means every path of the proxies. */
    public record RuntimeApiProduct(String name, List<String> proxies, List<String> paths, List<String> scopes,
                                    RuntimeQuota quota, Map<String, String> attributes) {

        public RuntimeApiProduct {
            proxies = proxies == null ? List.of() : List.copyOf(proxies);
            paths = paths == null ? List.of() : List.copyOf(paths);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            attributes = copy(attributes);
        }
    }

    public record RuntimeQuota(long count, int interval, String timeUnit) {
    }

    /** A credential the gateway may honour; only SHA-256 hashes of the key and secret are present. */
    /** {@code certificateFingerprints}: SHA-256 of the client certificates registered on this credential (mTLS). */
    public record RuntimeCredential(String id, String keyHash, String secretHash, Instant expiresAt, String appId,
                                    String appName, Map<String, String> appAttributes, String developerId,
                                    String developerEmail, List<String> apiProducts, List<String> certificateFingerprints) {

        @JsonCreator
        public RuntimeCredential {
            appAttributes = copy(appAttributes);
            apiProducts = apiProducts == null ? List.of() : List.copyOf(apiProducts);
            certificateFingerprints = certificateFingerprints == null ? List.of() : List.copyOf(certificateFingerprints);
        }

        public RuntimeCredential(String id, String keyHash, String secretHash, Instant expiresAt, String appId,
                                 String appName, Map<String, String> appAttributes, String developerId,
                                 String developerEmail, List<String> apiProducts) {
            this(id, keyHash, secretHash, expiresAt, appId, appName, appAttributes, developerId, developerEmail,
                    apiProducts, List.of());
        }
    }

    /**
     * Mutual TLS to a target: the gateway's client certificate chain and PKCS #8 key (PEM, decrypted by the
     * management plane), CA certificates to trust instead of the JVM defaults, and hostname verification.
     */
    public record RuntimeTargetTls(String clientCertificate, String clientKey, String trustedCertificates,
                                   boolean verifyHostname) {

        /** Identifies the material, so equal settings share one HTTP client. */
        public String fingerprint() {
            return com.portica.common.security.Hashing.sha256Hex(String.valueOf(clientCertificate) + "|"
                    + String.valueOf(clientKey) + "|" + String.valueOf(trustedCertificates) + "|" + verifyHostname);
        }
    }

    /** A key value map readable in the environment ({@code scope} environment or organization), plain values. */
    public record RuntimeKeyValueMap(String name, String scope, boolean encrypted, Map<String, String> entries) {

        public RuntimeKeyValueMap {
            entries = copy(entries);
        }
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
