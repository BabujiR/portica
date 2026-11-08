package com.portica.aigateway.quota;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/** Redis counters: INCRBY plus PEXPIRE on creation in one Lua script. */
public class RedisTokenQuotaStore implements TokenQuotaStore {

    private static final RedisScript<Long> ADD = RedisScript.of("""
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            if value == tonumber(ARGV[1]) then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return value
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisTokenQuotaStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> get(String key) {
        return redis.opsForValue().get("portica:" + key).map(Long::parseLong).defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> add(String key, long delta, Duration ttl) {
        return redis.execute(ADD, List.of("portica:" + key), List.of(Long.toString(delta), Long.toString(ttl.toMillis())))
                .next();
    }
}
