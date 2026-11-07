package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.VerifyApiKeyConfig;
import com.portica.common.policy.template.MessageTemplate;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.credentials.AppCredential;
import com.portica.gateway.policy.credentials.ApiProductGrant;
import com.portica.gateway.policy.credentials.AppVariables;
import com.portica.gateway.policy.credentials.CredentialResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * VerifyAPIKey. The key must belong to an approved app of an active developer, must not be expired,
 * and one of its approved API products must include this proxy (and path). Sets
 * {@code verifyapikey.<name>.client_id}, {@code .app.name}, {@code .app.id}, {@code .developer.email},
 * {@code .apiproduct.name} (the product that matched) and the shared app variables (see
 * {@link AppVariables}). Faults: {@code FailedToResolveAPIKey} (401) without a key,
 * {@code InvalidApiKey} (401) for unknown or revoked keys, {@code ApiKeyExpired} (401) and
 * {@code NoApiProductMatchFound} (403) when no product of the key includes this API.
 */
public final class VerifyApiKeyPolicy implements Policy {

    private final String name;
    private final MessageTemplate source;
    private final CredentialResolver credentials;
    private final Clock clock;

    public VerifyApiKeyPolicy(String name, VerifyApiKeyConfig config, CredentialResolver credentials) {
        this(name, config, credentials, Clock.systemUTC());
    }

    public VerifyApiKeyPolicy(String name, VerifyApiKeyConfig config, CredentialResolver credentials, Clock clock) {
        this.name = name;
        this.source = MessageTemplate.of(config.apiKey());
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        String key = ctx.render(source).strip();
        if (key.isEmpty()) {
            return Mono.error(PolicyFault.of("FailedToResolveAPIKey", 401, "An API key is required"));
        }
        MessageContext.ProxyInfo proxy = ctx.proxy();
        AppCredential app = credentials.resolve(proxy.organization(), proxy.environment(), key).orElse(null);
        if (app == null) {
            return Mono.error(PolicyFault.of("InvalidApiKey", 401, "The API key is not valid"));
        }
        if (app.expired(clock.instant())) {
            return Mono.error(PolicyFault.of("ApiKeyExpired", 401, "The API key has expired"));
        }
        ApiProductGrant product = app.productFor(proxy.proxy(), ctx.request().pathSuffix()).orElse(null);
        if (product == null) {
            return Mono.error(PolicyFault.of("NoApiProductMatchFound", 403,
                    "The API key is not authorized for this API resource"));
        }
        AppVariables.publish(ctx, MessageContext.prefix("verifyapikey", name), app, product);
        return Mono.empty();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final CredentialResolver credentials;

        public Factory(CredentialResolver credentials) {
            this.credentials = credentials;
        }

        @Override
        public PolicyType type() {
            return PolicyType.VERIFY_API_KEY;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new VerifyApiKeyPolicy(name, (VerifyApiKeyConfig) config, credentials);
        }
    }
}
