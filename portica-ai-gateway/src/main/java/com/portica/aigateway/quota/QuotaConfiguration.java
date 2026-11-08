package com.portica.aigateway.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** Redis by default ({@code portica.ai.quota-store=redis}); {@code memory} for one instance or tests. */
@Configuration
public class QuotaConfiguration {

    @Bean
    @ConditionalOnProperty(name = "portica.ai.quota-store", havingValue = "redis", matchIfMissing = true)
    TokenQuotaStore redisTokenQuotaStore(ReactiveStringRedisTemplate redis) {
        return new RedisTokenQuotaStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "portica.ai.quota-store", havingValue = "memory")
    TokenQuotaStore memoryTokenQuotaStore() {
        return new MemoryTokenQuotaStore();
    }
}
