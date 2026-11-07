package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.RegexProtectionConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RegularExpressionProtection. Values are checked decoded (so {@code %27} is seen as {@code '}).
 * Fault: {@code ThreatDetected} (400). The matched pattern is logged, never returned to the client.
 */
public final class RegexProtectionPolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(RegexProtectionPolicy.class);

    private final String name;
    private final List<Pattern> path;
    private final Map<String, List<Pattern>> queryParams = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> headers = new LinkedHashMap<>();
    private final List<Pattern> body;

    public RegexProtectionPolicy(String name, RegexProtectionConfig config) {
        this.name = name;
        this.path = compile(config.path());
        config.queryParams().forEach((k, v) -> queryParams.put(k, compile(v)));
        config.headers().forEach((k, v) -> headers.put(k, compile(v)));
        this.body = compile(config.body());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String decodedPath = decode(ctx.request().path());
        if (matches(path, decodedPath, "path")) {
            return threat();
        }
        for (Map.Entry<String, List<Pattern>> e : queryParams.entrySet()) {
            for (String value : values(ctx.request().queryParams(), e.getKey())) {
                if (matches(e.getValue(), value, "query parameter " + e.getKey())) {
                    return threat();
                }
            }
        }
        for (Map.Entry<String, List<Pattern>> e : headers.entrySet()) {
            List<String> values = new ArrayList<>();
            if (e.getKey().equals("*")) {
                ctx.request().headers().forEach((n, v) -> values.addAll(v));
            } else {
                values.addAll(ctx.request().headers().getOrEmpty(e.getKey()));
            }
            for (String value : values) {
                if (matches(e.getValue(), value, "header " + e.getKey())) {
                    return threat();
                }
            }
        }
        byte[] content = ctx.request().body();
        if (!body.isEmpty() && content != null && matches(body, new String(content, StandardCharsets.UTF_8), "body")) {
            return threat();
        }
        return Mono.empty();
    }

    private boolean matches(List<Pattern> patterns, String value, String where) {
        if (value == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(value).find()) {
                log.info("Policy {} blocked a request: {} matched /{}/", name, where, p.pattern());
                return true;
            }
        }
        return false;
    }

    private static List<String> values(Map<String, List<String>> params, String name) {
        List<String> values = new ArrayList<>();
        if (name.equals("*")) {
            params.values().forEach(values::addAll);
        } else if (params.get(name) != null) {
            values.addAll(params.get(name));
        }
        return values;
    }

    private static Mono<Void> threat() {
        return Mono.error(PolicyFault.of("ThreatDetected", 400, "The request was blocked"));
    }

    private static List<Pattern> compile(List<String> patterns) {
        return patterns.stream().map(Pattern::compile).toList();
    }

    private static String decode(String s) {
        try {
            return UriUtils.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    @Override
    public boolean requiresRequestBody() {
        return !body.isEmpty();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.REGULAR_EXPRESSION_PROTECTION;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new RegexProtectionPolicy(name, (RegexProtectionConfig) config);
        }
    }
}
