package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance counters (tests, local development). Expired entries are purged opportunistically. */
public class InMemoryCounterStore implements CounterStore {

    private record Entry(long value, long expiresAtMillis) {
    }

    private final Map<String, Entry> counters = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryCounterStore() {
        this(Clock.systemUTC());
    }

    public InMemoryCounterStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Long> increment(String key, long delta, Duration ttl) {
        return Mono.fromSupplier(() -> {
            long now = clock.millis();
            if (counters.size() > 10_000) {
                counters.values().removeIf(e -> e.expiresAtMillis() <= now);
            }
            Entry updated = counters.compute(key, (k, e) -> e == null || e.expiresAtMillis() <= now
                    ? new Entry(delta, now + ttl.toMillis())
                    : new Entry(e.value() + delta, e.expiresAtMillis()));
            return updated.value();
        });
    }
}
