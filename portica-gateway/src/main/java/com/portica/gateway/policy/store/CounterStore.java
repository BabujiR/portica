package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Atomic counters with expiry, shared by gateway instances (Quota). */
public interface CounterStore {

    /** Adds {@code delta} to {@code key} (created with {@code ttl} on first use) and returns the new total. */
    Mono<Long> increment(String key, long delta, Duration ttl);
}
