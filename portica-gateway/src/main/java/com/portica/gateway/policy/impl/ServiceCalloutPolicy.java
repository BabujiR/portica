package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.ServiceCalloutConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ServiceCallout. Response bodies up to 1 MB are kept. Sets
 * {@code servicecallout.<name>.response.status.code}, {@code .response.content},
 * {@code .response.header.<lower-case name>}; on tolerated errors {@code servicecallout.<name>.failed}.
 * Fault: {@code ServiceCalloutFailed} (502) when {@code failOnError} is true.
 */
public final class ServiceCalloutPolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(ServiceCalloutPolicy.class);

    private final String name;
    private final ServiceCalloutConfig config;
    private final MessageTemplate url;
    private final Map<String, MessageTemplate> headers = new LinkedHashMap<>();
    private final MessageTemplate body;
    private final WebClient client;

    public ServiceCalloutPolicy(String name, ServiceCalloutConfig config, WebClient client) {
        this.name = name;
        this.config = config;
        this.url = MessageTemplate.of(config.url());
        config.headers().forEach((k, v) -> headers.put(k, MessageTemplate.of(v)));
        this.body = config.body() == null ? null : MessageTemplate.of(config.body());
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String prefix = MessageContext.prefix("servicecallout", name);
        URI target;
        try {
            target = URI.create(ctx.render(url));
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || target.getHost() == null) {
                throw new IllegalArgumentException("not an http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            return failed(ctx, prefix, "invalid callout URL");
        }
        WebClient.RequestBodySpec request = client.method(HttpMethod.valueOf(config.method())).uri(target)
                .headers(h -> headers.forEach((k, t) -> h.set(k, ctx.render(t))));
        WebClient.RequestHeadersSpec<?> spec = body == null ? request : request.bodyValue(ctx.render(body));
        return spec.exchangeToMono(response -> response.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(bytes -> {
                            ctx.setVariable(prefix + "response.status.code", response.statusCode().value());
                            ctx.setVariable(prefix + "response.content", new String(bytes, StandardCharsets.UTF_8));
                            response.headers().asHttpHeaders().forEach((h, values) -> ctx.setVariable(
                                    prefix + "response.header." + h.toLowerCase(Locale.ROOT),
                                    values.isEmpty() ? null : values.get(0)));
                            return response.statusCode().value();
                        }))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .flatMap(status -> status >= 400 ? failed(ctx, prefix, "status " + status).then(Mono.<Integer>just(status))
                        : Mono.just(status))
                .onErrorResume(e -> !(e instanceof PolicyFault), e -> {
                    log.warn("Service callout {} failed: {}", name, e.toString());
                    return failed(ctx, prefix, e.getClass().getSimpleName()).then(Mono.<Integer>empty());
                })
                .then();
    }

    private Mono<Void> failed(MessageContext ctx, String prefix, String reason) {
        ctx.setVariable(prefix + "failed", true);
        if (config.failOnError()) {
            return Mono.error(PolicyFault.of("ServiceCalloutFailed", 502, "A dependent service call failed (" + reason + ")"));
        }
        return Mono.empty();
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final WebClient client = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();

        @Override
        public PolicyType type() {
            return PolicyType.SERVICE_CALLOUT;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new ServiceCalloutPolicy(name, (ServiceCalloutConfig) config, client);
        }
    }
}
