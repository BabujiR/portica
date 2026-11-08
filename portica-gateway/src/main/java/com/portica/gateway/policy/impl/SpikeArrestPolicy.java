package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.SpikeArrestConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.store.InMemoryRateLimiterStore;
import com.portica.gateway.policy.store.RateLimiterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.function.LongSupplier;

/**
 * SpikeArrest: spaces requests {@code 1/rate} apart per identifier, allowing {@code burst} back to back
 * (GCRA). With the Redis policy store every gateway instance shares the limit; with the in-memory store
 * each instance enforces it alone. Fault: {@code SpikeArrestViolation} (429, with {@code Retry-After}).
 * When Redis can't be reached the call goes through ({@code failOpen}, the default) or fails with
 * {@code SpikeArrestUnavailable} (503).
 */
public final class SpikeArrestPolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(SpikeArrestPolicy.class);

    private final String name;
    private final double ratePerSecond;
    private final int burst;
    private final boolean failOpen;
    private final MessageTemplate identifier;
    private final RateLimiterStore store;

    public SpikeArrestPolicy(String name, SpikeArrestConfig config, RateLimiterStore store) {
        this.name = name;
        this.ratePerSecond = config.ratePerSecond();
        this.burst = config.burst();
        this.failOpen = config.failOpen();
        this.identifier = MessageTemplate.of(config.identifier());
        this.store = store;
    }

    /** A per-instance limiter on the given clock (tests). */
    public SpikeArrestPolicy(String name, SpikeArrestConfig config, LongSupplier nanoClock) {
        this(name, config, new InMemoryRateLimiterStore(nanoClock));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        MessageContext.ProxyInfo p = ctx.proxy();
        String key = p == null ? "spike:" + name + ":" + ctx.render(identifier)
                : "spike:%s:%s:%s:%s:%s".formatted(p.organization(), p.environment(), p.proxy(), name, ctx.render(identifier));
        return store.acquire(key, ratePerSecond, burst)
                .onErrorResume(e -> {
                    if (failOpen) {
                        log.warn("SpikeArrest store unavailable, allowing request ({}): {}", name, e.toString());
                        return Mono.just(new RateLimiterStore.Decision(true, 0));
                    }
                    return Mono.error(PolicyFault.of("SpikeArrestUnavailable", 503, "Rate limit cannot be checked right now"));
                })
                .flatMap(d -> d.allowed() ? Mono.<Void>empty()
                        : Mono.error(PolicyFault.of("SpikeArrestViolation", 429, "Too many requests; slow down")
                        .header("Retry-After", Long.toString(Math.max(1, (d.retryAfterMillis() + 999) / 1000)))));
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final RateLimiterStore store;

        public Factory(RateLimiterStore store) {
            this.store = store;
        }

        @Override
        public PolicyType type() {
            return PolicyType.SPIKE_ARREST;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new SpikeArrestPolicy(name, (SpikeArrestConfig) config, store);
        }
    }
}
