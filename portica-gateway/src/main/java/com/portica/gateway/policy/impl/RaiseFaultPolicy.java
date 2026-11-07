package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.RaiseFaultConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.ProxyResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** RaiseFault: returns the configured response. In a fault rule it replaces the error response. */
public final class RaiseFaultPolicy implements Policy {

    private final String name;
    private final RaiseFaultConfig config;
    private final Map<String, MessageTemplate> headers = new LinkedHashMap<>();
    private final MessageTemplate body;

    public RaiseFaultPolicy(String name, RaiseFaultConfig config) {
        this.name = name;
        this.config = config;
        config.headers().forEach((k, v) -> headers.put(k, MessageTemplate.of(v)));
        this.body = config.body() == null ? null : MessageTemplate.of(config.body());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String renderedBody = body == null ? null : ctx.render(body);
        if (ctx.phase() == PolicyPhase.FAULT) {
            HttpHeaders h = new HttpHeaders();
            headers.forEach((k, t) -> h.set(k, ctx.render(t)));
            byte[] bytes = null;
            if (renderedBody != null) {
                h.set(HttpHeaders.CONTENT_TYPE, config.contentType());
                bytes = renderedBody.getBytes(StandardCharsets.UTF_8);
            }
            ctx.respond(new ProxyResponse(config.statusCode(), h, bytes));
            return Mono.empty();
        }
        PolicyFault fault = PolicyFault.of(config.faultName(), config.statusCode(), "Request rejected by " + name);
        headers.forEach((k, t) -> fault.header(k, ctx.render(t)));
        if (renderedBody != null) {
            fault.body(renderedBody, config.contentType());
        }
        return Mono.error(fault);
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.RAISE_FAULT;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new RaiseFaultPolicy(name, (RaiseFaultConfig) config);
        }
    }
}
