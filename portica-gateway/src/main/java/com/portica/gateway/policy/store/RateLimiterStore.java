package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

/**
 * Rate limiting with the generic cell rate algorithm (GCRA): requests are spaced {@code 1/rate} apart,
 * with up to {@code burst} arriving back to back. Equivalent to a token bucket of size {@code burst}
 * refilled at {@code rate}, but needs only one number per key.
 */
public interface RateLimiterStore {

    /** @param retryAfterMillis when refused, how long until a request would be allowed */
    record Decision(boolean allowed, long retryAfterMillis) {
    }

    Mono<Decision> acquire(String key, double ratePerSecond, int burst);
}
