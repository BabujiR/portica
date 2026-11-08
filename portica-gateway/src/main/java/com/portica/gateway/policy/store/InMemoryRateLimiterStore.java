package com.portica.gateway.policy.store;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** GCRA per gateway instance (tests, single instance). Keeps at most 100,000 keys, least recently used out. */
public class InMemoryRateLimiterStore implements RateLimiterStore {

    private static final int MAX_KEYS = 100_000;

    private final LongSupplier nanoClock;
    /** key -> theoretical arrival time of the next request, in nanoseconds. */
    private final Map<String, Long> tats = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_KEYS;
        }
    };

    public InMemoryRateLimiterStore() {
        this(System::nanoTime);
    }

    public InMemoryRateLimiterStore(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    @Override
    public Mono<Decision> acquire(String key, double ratePerSecond, int burst) {
        return Mono.fromSupplier(() -> decide(key, ratePerSecond, burst));
    }

    public Decision decide(String key, double ratePerSecond, int burst) {
        long now = nanoClock.getAsLong();
        double interval = 1_000_000_000.0 / ratePerSecond;
        double tolerance = interval * (Math.max(1, burst) - 1);
        synchronized (tats) {
            Long stored = tats.get(key);
            double tat = stored == null || stored < now ? now : stored;
            if (now < tat - tolerance) {
                return new Decision(false, (long) Math.ceil((tat - tolerance - now) / 1_000_000.0));
            }
            tats.put(key, (long) (tat + interval));
            return new Decision(true, 0);
        }
    }
}
