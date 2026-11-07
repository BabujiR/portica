package com.portica.gateway.policy;

import reactor.core.publisher.Mono;

/**
 * The Policy SPI. Implementations are created once per deployed revision by a {@link PolicyFactory}
 * and must be thread-safe (one instance serves concurrent requests; per-request state belongs in
 * {@link MessageContext}).
 * <p>
 * To add a policy type: add a config record and a {@code PolicyType} constant in portica-common, then
 * a Spring {@code @Component} implementing {@link PolicyFactory}. The engine needs no changes.
 */
public interface Policy {

    String name();

    /**
     * Runs the policy. Signal rejection with {@code Mono.error(PolicyFault)}; end the request flow
     * early with {@link MessageContext#respond(ProxyResponse)}.
     */
    Mono<Void> execute(MessageContext ctx);

    /** Whether the request body must be buffered before the request flow (default false). */
    default boolean requiresRequestBody() {
        return false;
    }

    /**
     * Whether the target response must be buffered when this policy is attached at {@code placement}.
     * Default: buffer when attached to the response flow (the policy may read or replace the body, or
     * fault). Header-only response policies return false so responses keep streaming.
     */
    default boolean requiresBufferedResponse(PolicyPhase placement) {
        return placement == PolicyPhase.RESPONSE;
    }
}
