package com.portica.gateway.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;

import java.time.Instant;
import java.util.List;

/**
 * Gateway-side copy of the management plane's runtime contract
 * ({@code com.portica.management.runtime.RuntimeDtos}). Unknown fields are ignored, so the
 * management plane can add fields without breaking older gateways. Policy bundles use the shared
 * model from portica-common.
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
                                           Instant generatedAt, List<RuntimeProxy> proxies,
                                           List<RuntimeSharedFlow> sharedFlows) {

        @JsonCreator // canonical constructor for JSON; the shorter one is for tests
        public RuntimeEnvironmentConfig {
            proxies = proxies == null ? List.of() : List.copyOf(proxies);
            sharedFlows = sharedFlows == null ? List.of() : List.copyOf(sharedFlows);
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

    public record RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                               int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies,
                               ProxyFlows flows) {

        @JsonCreator
        public RuntimeProxy {
            policies = policies == null ? List.of() : List.copyOf(policies);
            flows = flows == null ? ProxyFlows.EMPTY : flows;
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

    /** {@code org/env}, the identity of an environment across the system. */
    public static final class EnvironmentKey {

        private EnvironmentKey() {
        }

        public static String of(String organization, String environment) {
            return organization + "/" + environment;
        }
    }
}
