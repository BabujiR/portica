package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.VerifyJwtConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * VerifyJWT. Sets {@code jwt.<name>.claim.<claim>}, {@code .subject}, {@code .issuer}, {@code .valid}.
 * Faults: {@code JwtMissing} (401), {@code JwtInvalid} (401).
 */
public final class VerifyJwtPolicy implements Policy {

    private static final Logger log = LoggerFactory.getLogger(VerifyJwtPolicy.class);

    private final String name;
    private final VerifyJwtConfig config;
    private final MessageTemplate source;
    private final MessageTemplate secret;
    private final OAuth2TokenValidator<Jwt> validator;
    private final ReactiveJwtDecoder fixedDecoder;

    public VerifyJwtPolicy(String name, VerifyJwtConfig config) {
        this.name = name;
        this.config = config;
        this.source = MessageTemplate.of(config.source());
        this.secret = config.isHmac() ? MessageTemplate.of(config.secret()) : null;
        this.validator = JwtSupport.validator(config.clockSkewSeconds(), config.issuer(), config.audiences(),
                config.subject(), config.claims());
        if (!config.isHmac()) {
            this.fixedDecoder = JwtSupport.jwksDecoder(config.jwksUri(), config.algorithm(), validator);
        } else if (secret.isConstant()) {
            this.fixedDecoder = JwtSupport.hmacDecoder(config.secret(), config.algorithm(), validator);
        } else {
            this.fixedDecoder = null; // secret comes from a variable: build per request
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String token = JwtSupport.bearer(ctx.render(source));
        if (token.isEmpty()) {
            return Mono.error(PolicyFault.of("JwtMissing", 401, "A JWT is required"));
        }
        ReactiveJwtDecoder decoder = fixedDecoder != null ? fixedDecoder
                : JwtSupport.hmacDecoder(ctx.render(secret), config.algorithm(), validator);
        return decoder.decode(token)
                .onErrorMap(JwtException.class, e -> {
                    log.debug("JWT rejected by policy {}: {}", name, e.getMessage());
                    return PolicyFault.of("JwtInvalid", 401, "The JWT is not valid");
                })
                .doOnNext(jwt -> JwtSupport.publishClaims(ctx, MessageContext.prefix("jwt", name), jwt))
                .then();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return placement == PolicyPhase.RESPONSE;
    }

    @Component
    public static class Factory implements PolicyFactory {

        @Override
        public PolicyType type() {
            return PolicyType.VERIFY_JWT;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new VerifyJwtPolicy(name, (VerifyJwtConfig) config);
        }
    }
}
