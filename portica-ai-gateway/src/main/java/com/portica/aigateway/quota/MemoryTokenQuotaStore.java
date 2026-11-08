package com.portica.aigateway.quota;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance counters (tests, local development). */
public class MemoryTokenQuotaStore implements TokenQuotaStore {

    private record Entry(long value, long expiresAt) {
    }

    private final Map<String, Entry> counters = new ConcurrentHashMap<>();

    @Override
    public Mono<Long> get(String key) {
        return Mono.fromSupplier(() -> {
            Entry e = counters.get(key);
            return e == null || e.expiresAt() <= System.currentTimeMillis() ? 0L : e.value();
        });
    }

    @Override
    public Mono<Long> add(String key, long delta, Duration ttl) {
        return Mono.fromSupplier(() -> {
            long now = System.currentTimeMillis();
            if (counters.size() > 10_000) {
                counters.values().removeIf(e -> e.expiresAt() <= now);
            }
            return counters.compute(key, (k, e) -> e == null || e.expiresAt() <= now
                    ? new Entry(delta, now + ttl.toMillis())
                    : new Entry(e.value() + delta, e.expiresAt())).value();
        });
    }

    public void clear() {
        counters.clear();
    }
}
