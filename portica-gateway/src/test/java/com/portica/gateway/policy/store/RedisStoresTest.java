package com.portica.gateway.policy.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The distributed stores against a real Redis. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class RedisStoresTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @Test
    void countersAreAtomicAcrossGatewayInstances() {
        // Two store instances stand in for two gateway replicas sharing one Redis.
        CounterStore gatewayA = new RedisCounterStore(redis);
        CounterStore gatewayB = new RedisCounterStore(redis);
        String key = "test:" + UUID.randomUUID();

        Long highest = Flux.range(0, 200).parallel(8).runOn(Schedulers.parallel())
                .flatMap(i -> (i % 2 == 0 ? gatewayA : gatewayB).increment(key, 1, Duration.ofMinutes(1)))
                .sequential().reduce(Long::max).block(Duration.ofSeconds(30));

        assertThat(highest).isEqualTo(200L);
        Duration ttl = redis.getExpire("portica:" + key).block(Duration.ofSeconds(5));
        assertThat(ttl).isNotNull();
        assertThat(ttl.toSeconds()).isBetween(1L, 60L);
    }

    @Test
    void spikeArrestIsSharedByGatewayInstances() {
        // 10 per second, burst 5: across two "gateways" hammering one key, only the burst gets through at once
        RateLimiterStore gatewayA = new RedisRateLimiterStore(redis);
        RateLimiterStore gatewayB = new RedisRateLimiterStore(redis);
        String key = "spike:" + UUID.randomUUID();
        List<RateLimiterStore.Decision> decisions = Flux.range(0, 40).parallel(8).runOn(Schedulers.parallel())
                .flatMap(i -> (i % 2 == 0 ? gatewayA : gatewayB).acquire(key, 10, 5))
                .sequential().collectList().block(Duration.ofSeconds(30));
        long allowed = decisions.stream().filter(RateLimiterStore.Decision::allowed).count();
        assertThat(allowed).isBetween(5L, 6L);   // the burst, plus at most one refilled while the calls ran
        assertThat(decisions.stream().filter(d -> !d.allowed()).allMatch(d -> d.retryAfterMillis() > 0 && d.retryAfterMillis() <= 100))
                .isTrue();
        assertThat(redis.getExpire("portica:" + key).block(Duration.ofSeconds(5))).isNotNull();
    }

    @Test
    void spikeArrestAllowsAgainAfterTheInterval() throws InterruptedException {
        RateLimiterStore store = new RedisRateLimiterStore(redis);
        String key = "spike:" + UUID.randomUUID();
        assertThat(store.acquire(key, 20, 1).block().allowed()).isTrue();
        assertThat(store.acquire(key, 20, 1).block().allowed()).isFalse();
        Thread.sleep(60);
        assertThat(store.acquire(key, 20, 1).block().allowed()).isTrue();
    }

    @Test
    void weightedIncrementsAddUp() {
        CounterStore store = new RedisCounterStore(redis);
        String key = "test:" + UUID.randomUUID();
        assertThat(store.increment(key, 5, Duration.ofSeconds(30)).block()).isEqualTo(5L);
        assertThat(store.increment(key, 3, Duration.ofSeconds(30)).block()).isEqualTo(8L);
    }

    @Test
    void cachedResponsesRoundTrip() {
        CacheStore cache = new RedisCacheStore(redis);
        String key = "rc:" + UUID.randomUUID();
        byte[] body = "{\"id\":1,\"name\":\"Ünïcode\"}".getBytes(StandardCharsets.UTF_8);
        CachedResponse stored = new CachedResponse(200, Map.of("Content-Type", List.of("application/json"),
                "X-Multi", List.of("a", "b")), body);

        assertThat(cache.get(key).block()).isNull();
        cache.put(key, stored, Duration.ofSeconds(30)).block();
        CachedResponse loaded = cache.get(key).block();

        assertThat(loaded).isNotNull();
        assertThat(loaded.status()).isEqualTo(200);
        assertThat(loaded.headers()).containsEntry("X-Multi", List.of("a", "b"));
        assertThat(loaded.body()).isEqualTo(body);
    }
}
