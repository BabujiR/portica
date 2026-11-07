package com.portica.gateway.oauth;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/** Tokens in Redis as JSON under {@code portica:oauth:at:<sha256(token)>}, expiring with the token. */
public class RedisAccessTokenStore implements AccessTokenStore {

    private static final String PREFIX = "portica:oauth:at:";

    private final ReactiveStringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisAccessTokenStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> save(String tokenHash, AccessTokenRecord token, Duration ttl) {
        return Mono.defer(() -> redis.opsForValue().set(PREFIX + tokenHash, json.writeValueAsString(token), ttl))
                .then();
    }

    @Override
    public Mono<AccessTokenRecord> find(String tokenHash) {
        return redis.opsForValue().get(PREFIX + tokenHash).map(text -> json.readValue(text, AccessTokenRecord.class));
    }

    @Override
    public Mono<Void> delete(String tokenHash) {
        return redis.delete(PREFIX + tokenHash).then();
    }
}
