package com.portica.gateway.policy.store;

import com.portica.gateway.oauth.AccessTokenStore;
import com.portica.gateway.oauth.InMemoryAccessTokenStore;
import com.portica.gateway.oauth.RedisAccessTokenStore;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Picks the shared-state backend: Redis by default ({@code portica.gateway.policy.store=redis}),
 * or in-memory for a single instance / tests ({@code memory}).
 */
@Configuration
public class StoreConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "portica.gateway.policy.store", havingValue = "redis", matchIfMissing = true)
    static class Redis {

        @Bean
        CounterStore counterStore(ReactiveStringRedisTemplate redis) {
            return new RedisCounterStore(redis);
        }

        @Bean
        CacheStore cacheStore(ReactiveStringRedisTemplate redis) {
            return new RedisCacheStore(redis);
        }

        @Bean
        AccessTokenStore accessTokenStore(ReactiveStringRedisTemplate redis) {
            return new RedisAccessTokenStore(redis);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "portica.gateway.policy.store", havingValue = "memory")
    static class Memory {

        Memory() {
            LoggerFactory.getLogger(StoreConfiguration.class)
                    .warn("Using in-memory quota counters, cache and OAuth tokens: state is per gateway instance");
        }

        @Bean
        CounterStore counterStore() {
            return new InMemoryCounterStore();
        }

        @Bean
        CacheStore cacheStore() {
            return new InMemoryCacheStore();
        }

        @Bean
        AccessTokenStore accessTokenStore() {
            return new InMemoryAccessTokenStore();
        }
    }
}
