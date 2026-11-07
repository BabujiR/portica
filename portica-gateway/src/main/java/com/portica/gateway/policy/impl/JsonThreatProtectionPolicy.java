package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.JsonThreatProtectionConfig;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.transform.JsonXmlConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSONThreatProtection for request bodies with a JSON Content-Type. The size limit is checked before
 * parsing; the parser also enforces Jackson's own nesting and length safeguards.
 * Fault: {@code JsonThreatDetected} (400).
 */
public final class JsonThreatProtectionPolicy implements Policy {

    private final String name;
    private final JsonThreatProtectionConfig config;

    public JsonThreatProtectionPolicy(String name, JsonThreatProtectionConfig config) {
        this.name = name;
        this.config = config;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        byte[] body = ctx.request().body();
        String type = ctx.request().headers().getFirst(HttpHeaders.CONTENT_TYPE);
        if (body == null || body.length == 0 || type == null || !type.toLowerCase(Locale.ROOT).contains("json")) {
            return Mono.empty();
        }
        if (body.length > config.maxBodyBytes()) {
            return threat("body is larger than " + config.maxBodyBytes() + " bytes");
        }
        Object json;
        try {
            json = JsonXmlConverter.readJson(body);
        } catch (JsonXmlConverter.TransformException e) {
            return threat("body is not valid JSON");
        }
        String problem = check(json, 1);
        return problem == null ? Mono.empty() : threat(problem);
    }

    /** Returns the first limit exceeded, or null. */
    String check(Object value, int depth) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (depth > config.maxDepth()) {
                return "nesting is deeper than " + config.maxDepth();
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > config.maxObjectEntries()) {
                return "an object has more than " + config.maxObjectEntries() + " entries";
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (String.valueOf(e.getKey()).length() > config.maxNameLength()) {
                    return "a property name is longer than " + config.maxNameLength();
                }
                String p = check(e.getValue(), depth + 1);
                if (p != null) {
                    return p;
                }
            }
        } else if (value instanceof List<?> list) {
            if (list.size() > config.maxArrayElements()) {
                return "an array has more than " + config.maxArrayElements() + " elements";
            }
            for (Object v : list) {
                String p = check(v, depth + 1);
                if (p != null) {
                    return p;
                }
            }
        } else if (value instanceof String s && s.length() > config.maxStringLength()) {
            return "a string is longer than " + config.maxStringLength();
        }
        return null;
    }

    private static Mono<Void> threat(String detail) {
        return Mono.error(PolicyFault.of("JsonThreatDetected", 400, "JSON payload rejected: " + detail));
    }

    @Override
    public boolean requiresRequestBody() {
        return true;
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.JSON_THREAT_PROTECTION;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new JsonThreatProtectionPolicy(name, (JsonThreatProtectionConfig) config);
        }
    }
}
