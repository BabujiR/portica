package com.portica.gateway.policy.store;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * GCRA in Redis, one atomic Lua script per request, using Redis' own clock so gateway instances with
 * skewed clocks agree. Every instance shares the limit.
 */
public class RedisRateLimiterStore implements RateLimiterStore {

    // KEYS[1] key; ARGV[1] interval (µs between requests), ARGV[2] tolerance (µs of burst)
    // returns {allowed (1/0), retry after (µs)}
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> GCRA = RedisScript.of("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
            local interval = tonumber(ARGV[1])
            local tolerance = tonumber(ARGV[2])
            local tat = tonumber(redis.call('GET', KEYS[1]) or now)
            if tat < now then tat = now end
            if now < tat - tolerance then
              return {0, math.ceil(tat - tolerance - now)}
            end
            local next = tat + interval
            redis.call('SET', KEYS[1], string.format('%.0f', next), 'PX', math.ceil((next - now) / 1000) + 1000)
            return {1, 0}
            """, List.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRateLimiterStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Decision> acquire(String key, double ratePerSecond, int burst) {
        double interval = 1_000_000.0 / ratePerSecond;
        double tolerance = interval * (Math.max(1, burst) - 1);
        return redis.execute(GCRA, List.of("portica:" + key),
                        List.of(String.format(java.util.Locale.ROOT, "%.3f", interval), String.format(java.util.Locale.ROOT, "%.3f", tolerance)))
                .next()
                .map(result -> {
                    List<?> r = (List<?>) result;
                    boolean allowed = ((Number) r.get(0)).longValue() == 1;
                    long retryMicros = ((Number) r.get(1)).longValue();
                    return new Decision(allowed, (retryMicros + 999) / 1000);
                });
    }
}
