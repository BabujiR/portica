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
import com.portica.gateway.policy.credentials.CredentialResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * VerifyAPIKey. Sets {@code verifyapikey.<name>.client_id}, {@code .app.name}, {@code .developer.email}
 * and {@code .apiproduct.name} (first product). Faults: {@code FailedToResolveAPIKey} (401) when no key
 * is present, {@code InvalidApiKey} (401) when it is unknown.
 */
public final class VerifyApiKeyPolicy implements Policy {

    private final String name;
    private final MessageTemplate source;
    private final CredentialResolver credentials;

    public VerifyApiKeyPolicy(String name, VerifyApiKeyConfig config, CredentialResolver credentials) {
        this.name = name;
        this.source = MessageTemplate.of(config.apiKey());
        this.credentials = credentials;
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
        return credentials.resolveApiKey(ctx.proxy().organization(), key)
                .switchIfEmpty(Mono.error(() -> PolicyFault.of("InvalidApiKey", 401, "The API key is not valid")))
                .doOnNext(app -> publish(ctx, app))
                .then();
    }

    private void publish(MessageContext ctx, AppCredential app) {
        String p = MessageContext.prefix("verifyapikey", name);
        ctx.setVariable(p + "client_id", app.clientId());
        ctx.setVariable(p + "app.name", app.appName());
        ctx.setVariable(p + "developer.email", app.developerEmail());
        ctx.setVariable(p + "apiproduct.name", app.apiProducts().isEmpty() ? null : app.apiProducts().get(0));
        app.attributes().forEach((k, v) -> ctx.setVariable(p + "app.attribute." + k, v));
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
