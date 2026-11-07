package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.XmlToJsonConfig;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.transform.JsonXmlConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * XMLToJSON. Only converts bodies whose Content-Type is XML (or unset); others pass through.
 * Fault: {@code InvalidXML} (400 for requests, 502 for target responses).
 */
public final class XmlToJsonPolicy implements Policy {

    private final String name;
    private final XmlToJsonConfig config;

    public XmlToJsonPolicy(String name, XmlToJsonConfig config) {
        this.name = name;
        this.config = config;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        BodyTarget target = BodyTarget.of(ctx, config.target());
        if (target == null || target.body().length == 0) {
            return Mono.empty();
        }
        String type = target.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        if (type != null && !type.toLowerCase(java.util.Locale.ROOT).contains("xml")) {
            return Mono.empty();
        }
        try {
            target.setter().accept(JsonXmlConverter.xmlToJson(target.body(), config.recognizeNumbers(),
                    config.recognizeBooleans()));
        } catch (JsonXmlConverter.TransformException e) {
            int status = config.target().equals("request") ? 400 : 502;
            return Mono.error(PolicyFault.of("InvalidXML", status, "The " + config.target() + " body is not valid XML"));
        }
        target.headers().setContentType(MediaType.APPLICATION_JSON);
        return Mono.empty();
    }

    @Override
    public boolean requiresRequestBody() {
        return BodyTarget.needsRequestBody(config.target());
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return BodyTarget.needsBufferedResponse(config.target(), placement);
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.XML_TO_JSON;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new XmlToJsonPolicy(name, (XmlToJsonConfig) config);
        }
    }
}
