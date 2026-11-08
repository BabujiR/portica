package com.portica.gateway.policy.impl;

import com.portica.common.policy.PolicyType;
import com.portica.common.policy.config.PolicyConfig;
import com.portica.common.policy.config.VerifyClientCertificateConfig;
import com.portica.common.security.Pem;
import com.portica.gateway.policy.MessageContext;
import com.portica.gateway.policy.Policy;
import com.portica.gateway.policy.PolicyCompileContext;
import com.portica.gateway.policy.PolicyFactory;
import com.portica.gateway.policy.PolicyFault;
import com.portica.gateway.policy.PolicyPhase;
import com.portica.gateway.policy.credentials.ApiProductGrant;
import com.portica.gateway.policy.credentials.AppCredential;
import com.portica.gateway.policy.credentials.AppVariables;
import com.portica.gateway.policy.credentials.CredentialRegistry;
import com.portica.gateway.policy.engine.ClientCertificates;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Date;

/**
 * VerifyClientCertificate: the app is identified by the TLS client certificate it presented to the
 * gateway, registered on one of its keys (SHA-256 fingerprint). The key must be live and one of its
 * approved API products must include this proxy, exactly as for VerifyAPIKey. Sets
 * {@code verifyclientcertificate.<name>.*} and the shared app variables.
 * <p>
 * The gateway must run with TLS and {@code client-auth: want} (or {@code need}); the TLS layer checks the
 * chain against the configured trust store, this policy pins the certificate to an app.
 * Faults: {@code ClientCertificateRequired} (401), {@code ClientCertificateExpired} (401),
 * {@code UnknownClientCertificate} (401), {@code CertificateNotBoundToApp} (403, {@code bindToApp}) and
 * {@code NoApiProductMatchFound} (403).
 */
public final class VerifyClientCertificatePolicy implements Policy {

    private final String name;
    private final boolean bindToApp;
    private final CredentialRegistry registry;
    private final Clock clock;

    public VerifyClientCertificatePolicy(String name, VerifyClientCertificateConfig config, CredentialRegistry registry,
                                         Clock clock) {
        this.name = name;
        this.bindToApp = config.bindToApp();
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<Void> execute(MessageContext ctx) {
        X509Certificate certificate = ClientCertificates.of(ctx);
        if (certificate == null) {
            return Mono.error(PolicyFault.of("ClientCertificateRequired", 401, "A TLS client certificate is required"));
        }
        try {
            certificate.checkValidity(Date.from(clock.instant()));
        } catch (CertificateExpiredException | CertificateNotYetValidException e) {
            return Mono.error(PolicyFault.of("ClientCertificateExpired", 401, "The client certificate is not valid now"));
        }
        MessageContext.ProxyInfo proxy = ctx.proxy();
        AppCredential app = registry.index(proxy.organization(), proxy.environment())
                .findByCertificate(Pem.fingerprint(certificate)).orElse(null);
        if (app == null) {
            return Mono.error(PolicyFault.of("UnknownClientCertificate", 401, "The client certificate is not registered to an app"));
        }
        if (app.expired(clock.instant())) {
            return Mono.error(PolicyFault.of("ClientCertificateExpired", 401, "The app key holding this certificate has expired"));
        }
        if (bindToApp) {
            Object identified = ctx.variable("developer.app.id");
            if (identified == null || !identified.toString().equals(app.appId())) {
                return Mono.error(PolicyFault.of("CertificateNotBoundToApp", 403,
                        "The client certificate doesn't belong to the app of the presented credential"));
            }
        }
        ApiProductGrant product = app.productFor(proxy.proxy(), ctx.request().pathSuffix()).orElse(null);
        if (product == null) {
            return Mono.error(PolicyFault.of("NoApiProductMatchFound", 403,
                    "The app is not authorized for this API resource"));
        }
        AppVariables.publish(ctx, MessageContext.prefix("verifyclientcertificate", name), app, product);
        return Mono.empty();
    }

    @Override
    public boolean requiresBufferedResponse(PolicyPhase placement) {
        return false;
    }

    @Component
    public static class Factory implements PolicyFactory {

        private final CredentialRegistry registry;

        public Factory(CredentialRegistry registry) {
            this.registry = registry;
        }

        @Override
        public PolicyType type() {
            return PolicyType.VERIFY_CLIENT_CERTIFICATE;
        }

        @Override
        public Policy create(String name, PolicyConfig config, PolicyCompileContext context) {
            return new VerifyClientCertificatePolicy(name, (VerifyClientCertificateConfig) config, registry, Clock.systemUTC());
        }
    }
}
