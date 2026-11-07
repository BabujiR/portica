package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Response cache shared by gateway instances. */
public interface CacheStore {

    Mono<CachedResponse> get(String key);

    Mono<Void> put(String key, CachedResponse response, Duration ttl);
}
