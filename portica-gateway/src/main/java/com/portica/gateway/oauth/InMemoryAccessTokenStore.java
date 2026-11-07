package com.portica.gateway.oauth;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance token store (tests, local development), capped at 100,000 live tokens. */
public class InMemoryAccessTokenStore implements AccessTokenStore {

    private static final int MAX_TOKENS = 100_000;

    private record Entry(AccessTokenRecord token, long expiresAtMillis) {
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAccessTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryAccessTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> save(String tokenHash, AccessTokenRecord token, Duration ttl) {
        return Mono.fromRunnable(() -> {
            long now = clock.millis();
            if (tokens.size() >= MAX_TOKENS) {
                tokens.values().removeIf(e -> e.expiresAtMillis() <= now);
                if (tokens.size() >= MAX_TOKENS) {
                    throw new IllegalStateException("In-memory token store is full");
                }
            }
            tokens.put(tokenHash, new Entry(token, now + ttl.toMillis()));
        });
    }

    @Override
    public Mono<AccessTokenRecord> find(String tokenHash) {
        return Mono.defer(() -> {
            Entry e = tokens.get(tokenHash);
            if (e == null) {
                return Mono.empty();
            }
            if (e.expiresAtMillis() <= clock.millis()) {
                tokens.remove(tokenHash, e);
                return Mono.empty();
            }
            return Mono.just(e.token());
        });
    }

    @Override
    public Mono<Void> delete(String tokenHash) {
        return Mono.fromRunnable(() -> tokens.remove(tokenHash));
    }
}
