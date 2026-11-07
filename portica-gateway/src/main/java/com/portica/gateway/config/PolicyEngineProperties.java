package com.portica.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy engine settings under {@code portica.gateway.policy.*}.
 *
 * @param maxRequestBodyBytes  largest request body buffered for policies (default 10 MB; larger -> 413)
 * @param maxResponseBodyBytes largest target response buffered for policies (default 10 MB; larger -> 502)
 * @param trustForwardedFor    take {@code client.ip} from the first X-Forwarded-For entry. Enable only
 *                             behind a load balancer that sets it, or clients can spoof their IP.
 * @param store                {@code redis} (default; shared across instances) or {@code memory}
 *                             (single instance, tests)
 */
@ConfigurationProperties(prefix = "portica.gateway.policy")
public record PolicyEngineProperties(Integer maxRequestBodyBytes, Integer maxResponseBodyBytes,
                                     Boolean trustForwardedFor, String store) {

    public PolicyEngineProperties {
        maxRequestBodyBytes = maxRequestBodyBytes == null ? 10 * 1024 * 1024 : maxRequestBodyBytes;
        maxResponseBodyBytes = maxResponseBodyBytes == null ? 10 * 1024 * 1024 : maxResponseBodyBytes;
        trustForwardedFor = trustForwardedFor != null && trustForwardedFor;
        store = store == null ? "redis" : store;
    }
}
