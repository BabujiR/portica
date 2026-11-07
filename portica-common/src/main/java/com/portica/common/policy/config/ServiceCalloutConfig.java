package com.portica.common.policy.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ServiceCallout: calls another HTTP service mid-flow and stores the response in variables
 * {@code servicecallout.<policy>.response.status.code}, {@code .response.content} and
 * {@code .response.header.<name>}.
 *
 * @param url         target URL (template)
 * @param method      default GET
 * @param headers     request headers (templates)
 * @param body        request body (template)
 * @param timeoutMs   default 5000
 * @param failOnError raise a fault on network errors or 4xx/5xx (default true)
 */
public record ServiceCalloutConfig(String url, String method, Map<String, String> headers, String body,
                                   Long timeoutMs, Boolean failOnError) implements PolicyConfig {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head");

    public ServiceCalloutConfig {
        method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        timeoutMs = timeoutMs == null ? 5_000L : timeoutMs;
        failOnError = failOnError == null || failOnError;
    }

    @Override
    public List<String> validate() {
        return new Problems()
                .notBlank(url, "url").httpUrl(url, "url")
                .oneOf(method, METHODS, "method")
                .range(timeoutMs, 100, 60_000, "timeoutMs")
                .headerNames(headers.keySet(), "headers")
                .list();
    }
}
