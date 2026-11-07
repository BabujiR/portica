package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.QuotaConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.store.CounterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

/**
 * Quota with fixed windows shared across instances. Sets {@code ratelimit.<name>.allowed.count},
 * {@code .used.count}, {@code .available.count}, {@code .exceed.count}, {@code .expiry.time} (epoch ms).
 * Faults: {@code QuotaViolation} (429, with Retry-After), {@code QuotaUnavailable} (503, only when
 * {@code failOpen} is false and the store is down).
 */
public final class QuotaPolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(QuotaPolicy.class);

    private final String name;
    private final QuotaConfig config;
    private final MessageTemplate identifier;
    private final MessageTemplate weight;
    private final CounterStore counters;
    private final Clock clock;

    public QuotaPolicy(String name, QuotaConfig config, CounterStore counters, Clock clock) {
        this.name = name;
        this.config = config;
        this.identifier = MessageTemplate.of(config.identifier());
        this.weight = MessageTemplate.of(config.weight());
        this.counters = counters;
        this.clock = clock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        long windowMs = config.window().toMillis();
        long now = clock.millis();
        long windowStart = now - (now % windowMs);
        long windowEnd = windowStart + windowMs;
        long delta = weight(ctx);
        MessageContext.ProxyInfo p = ctx.proxy();
        String key = "quota:%s:%s:%s:%s:%s:%d".formatted(p.organization(), p.environment(), p.proxy(), name,
                ctx.render(identifier), windowStart);
        String prefix = MessageContext.prefix("ratelimit", name);
        long allowed = config.count();

        return counters.increment(key, delta, Duration.ofMillis(windowEnd - now + 1_000))
                .map(used -> {
                    long available = Math.max(0, allowed - used);
                    ctx.setVariable(prefix + "allowed.count", allowed);
                    ctx.setVariable(prefix + "used.count", Math.min(used, allowed));
                    ctx.setVariable(prefix + "available.count", available);
                    ctx.setVariable(prefix + "exceed.count", Math.max(0, used - allowed));
                    ctx.setVariable(prefix + "expiry.time", windowEnd);
                    long resetSeconds = Math.max(1, (windowEnd - now + 999) / 1000);
                    if (config.emitHeaders()) {
                        ctx.pendingResponseHeaders().set("RateLimit-Limit", Long.toString(allowed));
                        ctx.pendingResponseHeaders().set("RateLimit-Remaining", Long.toString(available));
                        ctx.pendingResponseHeaders().set("RateLimit-Reset", Long.toString(resetSeconds));
                    }
                    if (used > allowed) {
                        throw PolicyFault.of("QuotaViolation", 429, "Quota exceeded; try again later")
                                .header("Retry-After", Long.toString(resetSeconds));
                    }
                    return used;
                })
                .onErrorResume(e -> !(e instanceof PolicyFault), e -> {
                    if (config.failOpen()) {
                        log.warn("Quota store unavailable, allowing request ({}): {}", name, e.toString());
                        return Mono.empty();
                    }
                    return Mono.error(PolicyFault.of("QuotaUnavailable", 503, "Quota cannot be checked right now"));
                })
                .then();
    }

    private long weight(MessageContext ctx) {
        try {
            return Math.max(0, Long.parseLong(ctx.render(weight).strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false; // only adds headers
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final CounterStore counters;

        public Factory(CounterStore counters) {
            this.counters = counters;
        }

        @Override
        public PolicyType type() {
            return PolicyType.QUOTA;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new QuotaPolicy(name, (QuotaConfig) config, counters, Clock.systemUTC());
        }
    }
}
