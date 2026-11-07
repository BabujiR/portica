package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.AssignMessageConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.ProxyRequest;
import com.portica.gateway.policy.ProxyResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AssignMessage. With target {@code response} outside a response (e.g. in the request flow), header
 * changes are queued for the eventual response and body/status changes are ignored.
 */
public final class AssignMessagePolicy implements Policy {

    private final String name;
    private final AssignMessageConfig config;
    private final Map<String, MessageTemplate> setHeaders = new LinkedHashMap<>();
    private final Map<String, MessageTemplate> addHeaders = new LinkedHashMap<>();
    private final Map<String, MessageTemplate> setQuery = new LinkedHashMap<>();
    private final Map<String, MessageTemplate> setVariables = new LinkedHashMap<>();
    private final MessageTemplate verb;
    private final MessageTemplate path;
    private final MessageTemplate body;
    private final MessageTemplate contentType;

    public AssignMessagePolicy(String name, AssignMessageConfig config) {
        this.name = name;
        this.config = config;
        config.setHeaders().forEach((k, v) -> setHeaders.put(k, MessageTemplate.of(v)));
        config.addHeaders().forEach((k, v) -> addHeaders.put(k, MessageTemplate.of(v)));
        config.setQueryParams().forEach((k, v) -> setQuery.put(k, MessageTemplate.of(v)));
        config.setVariables().forEach((k, v) -> setVariables.put(k, MessageTemplate.of(v)));
        this.verb = config.setVerb() == null ? null : MessageTemplate.of(config.setVerb());
        this.path = config.setPath() == null ? null : MessageTemplate.of(config.setPath());
        this.body = config.setBody() == null ? null : MessageTemplate.of(config.setBody());
        this.contentType = config.setContentType() == null ? null : MessageTemplate.of(config.setContentType());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        setVariables.forEach((k, t) -> ctx.setVariable(k, ctx.render(t)));
        if (config.targetsResponse()) {
            ProxyResponse response = ctx.response();
            HttpHeaders headers = response != null ? response.headers() : ctx.pendingResponseHeaders();
            applyHeaders(ctx, headers);
            if (response != null) {
                if (config.setStatusCode() != null) {
                    response.setStatus(config.setStatusCode());
                }
                applyBody(ctx, response.headers(), bytes -> response.setBody(bytes));
            }
        } else {
            ProxyRequest request = ctx.request();
            applyHeaders(ctx, request.headers());
            config.removeQueryParams().forEach(request::removeQueryParam);
            setQuery.forEach((k, t) -> request.setQueryParam(k, ctx.render(t)));
            if (verb != null) {
                request.setMethod(HttpMethod.valueOf(ctx.render(verb).strip().toUpperCase(java.util.Locale.ROOT)));
            }
            if (path != null) {
                String p = ctx.render(path).strip();
                request.setPathSuffix(p.equals("/") ? "" : p);
            }
            applyBody(ctx, request.headers(), request::setBody);
        }
        return Mono.empty();
    }

    private void applyHeaders(MessageContext ctx, HttpHeaders headers) {
        config.removeHeaders().forEach(headers::remove);
        setHeaders.forEach((k, t) -> headers.set(k, ctx.render(t)));
        addHeaders.forEach((k, t) -> headers.add(k, ctx.render(t)));
    }

    private void applyBody(MessageContext ctx, HttpHeaders headers, java.util.function.Consumer<byte[]> setter) {
        if (body != null) {
            setter.accept(ctx.render(body).getBytes(StandardCharsets.UTF_8));
        }
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, ctx.render(contentType));
        }
    }

    @Override
    public boolean requiresRequestBody() {
        return config.templates().stream().anyMatch(t -> t.references("request.content"));
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        boolean readsResponseBody = config.templates().stream().anyMatch(t -> t.references("response.content"));
        // Header/status-only changes to the response keep it streaming.
        return config.targetsResponse() && placement == PolicyPhase.RESPONSE && (body != null || readsResponseBody);
    }


    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.ASSIGN_MESSAGE;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new AssignMessagePolicy(name, (AssignMessageConfig) config);
        }
    }
}
