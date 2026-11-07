package com.portica.management.runtime;

import com.portica.common.policy.FlowStep;
import com.portica.common.policy.PolicyDefinition;
import com.portica.common.policy.ProxyFlows;

import java.time.Instant;
import java.util.List;

/**
 * Contract between the management plane and gateways. Gateways keep their own copy of these
 * shapes, so changes must stay backwards compatible (add fields; never rename or remove).
 */
public final class RuntimeDtos {

    private RuntimeDtos() {
    }

    /** Cheap "what changed" list a gateway polls frequently. */
    public record RuntimeIndex(Instant generatedAt, List<EnvironmentVersion> environments) {
    }

    public record EnvironmentVersion(String organization, String environment, long configVersion) {
    }

    /** Everything a gateway needs to serve one environment. */
    public record RuntimeEnvironmentConfig(String organization, String environment, long configVersion,
                                           Instant generatedAt, List<RuntimeProxy> proxies,
                                           List<RuntimeSharedFlow> sharedFlows) {
    }

    /**
     * @param targetUri  scheme, host and port of the backend, e.g. {@code https://api.example.com:443}
     * @param targetPath path prefix on the backend ("" for none), e.g. {@code /v2}
     */
    public record RuntimeProxy(String name, int revision, String basePath, String targetUri, String targetPath,
                               int connectTimeoutMs, int readTimeoutMs, List<PolicyDefinition> policies,
                               ProxyFlows flows) {
    }

    /** A shared flow deployed in the environment, callable by FlowCallout policies. */
    public record RuntimeSharedFlow(String name, int revision, List<PolicyDefinition> policies, List<FlowStep> steps) {
    }
}
