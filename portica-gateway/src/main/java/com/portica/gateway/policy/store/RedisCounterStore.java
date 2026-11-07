package com.portica.gateway.policy.store;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Redis counters: INCRBY plus PEXPIRE on creation, atomically in one Lua script, so every gateway
 * instance sees the same count and keys never outlive their window.
 */
public class RedisCounterStore implements CounterStore {

    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            if value == tonumber(ARGV[1]) then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return value
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisCounterStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> increment(String key, long delta, Duration ttl) {
        return redis.execute(INCREMENT, List.of("portica:" + key), List.of(Long.toString(delta), Long.toString(ttl.toMillis())))
                .next();
    }
}
