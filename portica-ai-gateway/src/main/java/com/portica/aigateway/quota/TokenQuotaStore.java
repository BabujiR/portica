package com.portica.aigateway.quota;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Token counters per app and window, shared by AI gateway instances. */
public interface TokenQuotaStore {

    /** Current count (0 when absent). */
    Mono<Long> get(String key);

    /** Adds {@code delta} (created with {@code ttl} on first use) and returns the new total. */
    Mono<Long> add(String key, long delta, Duration ttl);
}
