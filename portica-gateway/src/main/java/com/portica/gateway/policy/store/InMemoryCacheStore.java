package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance response cache (tests, local development), capped at 10,000 entries. */
public class InMemoryCacheStore implements CacheStore {

    private record Entry(CachedResponse response, long expiresAtMillis) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCacheStore() {
        this(Clock.systemUTC());
    }

    public InMemoryCacheStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<CachedResponse> get(String key) {
        return Mono.defer(() -> {
            Entry e = entries.get(key);
            if (e == null || e.expiresAtMillis() <= clock.millis()) {
                return Mono.empty();
            }
            return Mono.just(e.response());
        });
    }

    @Override
    public Mono<Void> put(String key, CachedResponse response, Duration ttl) {
        return Mono.fromRunnable(() -> {
            long now = clock.millis();
            if (entries.size() >= 10_000) {
                entries.values().removeIf(e -> e.expiresAtMillis() <= now);
                if (entries.size() >= 10_000) {
                    return; // full: skip caching rather than grow without bound
                }
            }
            entries.put(key, new Entry(response, now + ttl.toMillis()));
        });
    }
}
