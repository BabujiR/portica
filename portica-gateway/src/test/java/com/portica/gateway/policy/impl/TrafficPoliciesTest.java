package com.portica.gateway.policy.impl;

import com.portica.common.policy.config.QuotaConfig;
import com.portica.common.policy.config.ResponseCacheConfig;
import com.portica.common.policy.config.SpikeArrestConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.store.CounterStore;
import com.portica.gateway.policy.store.InMemoryCacheStore;
import com.portica.gateway.policy.store.InMemoryCounterStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static com.portica.gateway.policy.TestMessages.request;
import static com.portica.gateway.policy.TestMessages.run;
import static com.portica.gateway.policy.TestMessages.text;
import static com.portica.gateway.policy.TestMessages.withResponse;
import static org.assertj.core.api.Assertions.assertThat;

class TrafficPoliciesTest {

    @Nested
    class Quota {

        private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T10:00:30Z"), ZoneOffset.UTC);

        @Test
        void allowsCountPerWindowThenFaultsWithRetryAfter() {
            QuotaPolicy quota = new QuotaPolicy("quota", new QuotaConfig(2L, 1, "minute", null, null, null, null),
                    new InMemoryCounterStore(clock), clock);

            MessageContext first = request("GET", "/").build();
            assertThat(run(quota, first)).isNull();
            assertThat(first.variable("ratelimit.quota.available.count")).isEqualTo(1L);
            assertThat(first.pendingResponseHeaders().getFirst("RateLimit-Remaining")).isEqualTo("1");
            assertThat(run(quota, request("GET", "/").build())).isNull();

            PolicyFault fault = run(quota, request("GET", "/").build());
            assertThat(fault.faultName()).isEqualTo("QuotaViolation");
            assertThat(fault.status()).isEqualTo(429);
            assertThat(fault.headers().getFirst("Retry-After")).isEqualTo("30"); // window ends at 10:01:00
        }

        @Test
        void countsSeparatelyPerIdentifier() {
            QuotaPolicy quota = new QuotaPolicy("quota",
                    new QuotaConfig(1L, null, null, "{request.header.x-client}", null, null, null),
                    new InMemoryCounterStore(clock), clock);

            assertThat(run(quota, request("GET", "/").header("x-client", "a").build())).isNull();
            assertThat(run(quota, request("GET", "/").header("x-client", "b").build())).isNull();
            assertThat(run(quota, request("GET", "/").header("x-client", "a").build())).isNotNull();
        }

        @Test
        void storeOutageFailsOpenOrClosedAsConfigured() {
            CounterStore broken = (key, delta, ttl) -> Mono.error(new IllegalStateException("redis down"));
            QuotaPolicy open = new QuotaPolicy("q", new QuotaConfig(1L, null, null, null, null, true, null), broken, clock);
            QuotaPolicy closed = new QuotaPolicy("q", new QuotaConfig(1L, null, null, null, null, false, null), broken, clock);

            assertThat(run(open, request("GET", "/").build())).isNull();
            assertThat(run(closed, request("GET", "/").build()).faultName()).isEqualTo("QuotaUnavailable");
        }

        @Test
        void inMemoryCounterIsAtomicUnderConcurrency() {
            InMemoryCounterStore store = new InMemoryCounterStore();
            Long last = Flux.range(0, 1000).parallel(8).runOn(reactor.core.scheduler.Schedulers.parallel())
                    .flatMap(i -> store.increment("k", 1, java.time.Duration.ofMinutes(1)))
                    .sequential().reduce(Long::max).block();
            assertThat(last).isEqualTo(1000L);
        }
    }

    @Nested
    class SpikeArrest {

        @Test
        void smoothsToRateWithBurst() {
            AtomicLong nanos = new AtomicLong(0);
            SpikeArrestPolicy policy = new SpikeArrestPolicy("spike", new SpikeArrestConfig("10ps", null, 2), nanos::get);

            assertThat(run(policy, request("GET", "/").build())).isNull();
            assertThat(run(policy, request("GET", "/").build())).isNull(); // burst of 2
            assertThat(run(policy, request("GET", "/").build()).faultName()).isEqualTo("SpikeArrestViolation");

            nanos.addAndGet(110_000_000L); // 110 ms later: one token back at 10 per second
            assertThat(run(policy, request("GET", "/").build())).isNull();
            assertThat(run(policy, request("GET", "/").build())).isNotNull();
        }

        @Test
        void perMinuteRatesAndSeparateIdentifiers() {
            AtomicLong nanos = new AtomicLong(0);
            SpikeArrestPolicy policy = new SpikeArrestPolicy("spike",
                    new SpikeArrestConfig("60pm", "{client.ip}", null), nanos::get);

            assertThat(run(policy, request("GET", "/").clientIp("1.1.1.1").build())).isNull();
            assertThat(run(policy, request("GET", "/").clientIp("2.2.2.2").build())).isNull();
            assertThat(run(policy, request("GET", "/").clientIp("1.1.1.1").build())).isNotNull();
            nanos.addAndGet(1_100_000_000L);
            assertThat(run(policy, request("GET", "/").clientIp("1.1.1.1").build())).isNull();
        }
    }

    @Nested
    class ResponseCache {

        private final InMemoryCacheStore store = new InMemoryCacheStore();
        private final ResponseCachePolicy cache = new ResponseCachePolicy("cache",
                new ResponseCacheConfig(60L, null, null, null, "request.header.cache-control == \"no-cache\"", null, null),
                store);

        @Test
        void missStoresFinalResponseThenHitShortCircuits() {
            MessageContext miss = request("GET", "/1?x=y").build();
            assertThat(run(cache, miss)).isNull();
            assertThat(miss.isShortCircuited()).isFalse();
            withResponse(miss, 200, "application/json", "{\"id\":1}");
            miss.response().headers().set("Set-Header", "kept");
            runCallbacks(miss);
            assertThat(miss.response().headers().getFirst("X-Portica-Cache")).isEqualTo("MISS");

            MessageContext hit = request("GET", "/1?x=y").build();
            assertThat(run(cache, hit)).isNull();
            assertThat(hit.isShortCircuited()).isTrue();
            assertThat(text(hit.response().body())).isEqualTo("{\"id\":1}");
            assertThat(hit.response().headers().getFirst("X-Portica-Cache")).isEqualTo("HIT");
            assertThat(hit.response().headers().getFirst("Set-Header")).isEqualTo("kept");
            assertThat(hit.variable("responsecache.cache.cachehit")).isEqualTo(true);
        }

        @Test
        void differentUrisPostsErrorsAndPrivateResponsesAreNotServedFromCache() {
            MessageContext error = request("GET", "/err").build();
            run(cache, error);
            withResponse(error, 500, "text/plain", "boom");
            runCallbacks(error);
            assertThat(isHit(request("GET", "/err").build())).isFalse();

            MessageContext privateResponse = request("GET", "/me").build();
            run(cache, privateResponse);
            withResponse(privateResponse, 200, "text/plain", "secret");
            privateResponse.response().headers().set("Cache-Control", "private");
            runCallbacks(privateResponse);
            assertThat(isHit(request("GET", "/me").build())).isFalse();

            MessageContext post = request("POST", "/p").build();
            run(cache, post);
            assertThat(post.responseCallbacks()).isEmpty();
        }

        @Test
        void skipLookupConditionBypassesTheCache() {
            MessageContext first = request("GET", "/2").build();
            run(cache, first);
            withResponse(first, 200, "text/plain", "v1");
            runCallbacks(first);

            assertThat(isHit(request("GET", "/2").header("cache-control", "no-cache").build())).isFalse();
            assertThat(isHit(request("GET", "/2").build())).isTrue();
        }

        private boolean isHit(MessageContext ctx) {
            run(cache, ctx);
            return ctx.isShortCircuited();
        }

        private void runCallbacks(MessageContext ctx) {
            Flux.fromIterable(ctx.responseCallbacks()).concatMap(c -> c.apply(ctx)).then().block();
        }
    }

}
