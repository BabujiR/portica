package com.portica.gateway.policy.store;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Redis response cache. Entries are JSON with a Base64 body, under {@code portica:cache:<key>}. */
public class RedisCacheStore implements CacheStore {

    public record Stored(int status, Map<String, List<String>> headers, String body) {
    }

    private final ReactiveStringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisCacheStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<CachedResponse> get(String key) {
        return redis.opsForValue().get("portica:cache:" + key)
                .map(text -> {
                    Stored s = json.readValue(text, Stored.class);
                    return new CachedResponse(s.status(), s.headers(), Base64.getDecoder().decode(s.body()));
                });
    }

    @Override
    public Mono<Void> put(String key, CachedResponse response, Duration ttl) {
        String text = json.writeValueAsString(new Stored(response.status(), response.headers(),
                Base64.getEncoder().encodeToString(response.body())));
        return redis.opsForValue().set("portica:cache:" + key, text, ttl).then();
    }
}
