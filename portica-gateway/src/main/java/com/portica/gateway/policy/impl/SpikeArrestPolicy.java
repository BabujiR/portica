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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * SpikeArrest: a token bucket per identifier, per gateway instance (like Apigee's per message
 * processor). With rate 10ps and burst 1, requests closer than 100 ms apart are rejected.
 * Fault: {@code SpikeArrestViolation} (429).
 */
public final class SpikeArrestPolicy implements Policy {

    private static final int MAX_BUCKETS = 10_000;

    private final String name;
    private final double tokensPerNano;
    private final double capacity;
    private final MessageTemplate identifier;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_BUCKETS;
        }
    };

    private static final class Bucket {
        double tokens;
        long last;
    }

    public SpikeArrestPolicy(String name, SpikeArrestConfig config, LongSupplier nanoClock) {
        this.name = name;
        this.tokensPerNano = config.ratePerSecond() / 1_000_000_000.0;
        this.capacity = config.burst();
        this.identifier = MessageTemplate.of(config.identifier());
        this.nanoClock = nanoClock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String id = ctx.render(identifier);
        long now = nanoClock.getAsLong();
        boolean allowed;
        synchronized (buckets) {
            Bucket b = buckets.get(id);
            if (b == null) {
                b = new Bucket();
                b.tokens = capacity;
                b.last = now;
                buckets.put(id, b);
            }
            b.tokens = Math.min(capacity, b.tokens + (now - b.last) * tokensPerNano);
            b.last = now;
            allowed = b.tokens >= 1.0;
            if (allowed) {
                b.tokens -= 1.0;
            }
        }
        return allowed ? Mono.empty()
                : Mono.error(PolicyFault.of("SpikeArrestViolation", 429, "Too many requests; slow down")
                .header("Retry-After", "1"));
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.SPIKE_ARREST;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new SpikeArrestPolicy(name, (SpikeArrestConfig) config, System::nanoTime);
        }
    }
}
