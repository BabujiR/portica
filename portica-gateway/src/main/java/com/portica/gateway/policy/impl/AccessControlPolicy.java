package com.portica.gateway.policy.impl;

import com.portica.common.net.CidrRange;
import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.AccessControlConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/** AccessControl: deny rules win; a non-empty allow list denies everything else. Fault: {@code AccessDenied} (403). */
public final class AccessControlPolicy implements Policy {

    private final String name;
    private final List<CidrRange> allow;
    private final List<CidrRange> deny;
    private final MessageTemplate source;

    public AccessControlPolicy(String name, AccessControlConfig config) {
        this.name = name;
        this.allow = config.allow().stream().map(CidrRange::parse).toList();
        this.deny = config.deny().stream().map(CidrRange::parse).toList();
        this.source = MessageTemplate.of(config.sourceAddress());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        Optional<byte[]> address = CidrRange.literal(ctx.render(source));
        boolean allowed = address.isPresent()
                && deny.stream().noneMatch(r -> r.contains(address.get()))
                && (allow.isEmpty() || allow.stream().anyMatch(r -> r.contains(address.get())));
        return allowed ? Mono.empty() : Mono.error(PolicyFault.of("AccessDenied", 403, "Access denied"));
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.ACCESS_CONTROL;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new AccessControlPolicy(name, (AccessControlConfig) config);
        }
    }
}
