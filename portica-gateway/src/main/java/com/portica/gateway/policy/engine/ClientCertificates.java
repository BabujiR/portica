package com.portica.gateway.policy.engine;

import com.portica.common.security.Pem;
import com.portica.gateway.policy.MessageContext;
import org.springframework.http.server.reactive.SslInfo;

import java.security.cert.X509Certificate;

/**
 * Makes the TLS client certificate (mutual TLS at the gateway) available to policies: the certificate
 * itself for {@code VerifyClientCertificate}, and variables for conditions and templates:
 * {@code tls.client.presented}, {@code tls.client.subject}, {@code tls.client.issuer},
 * {@code tls.client.fingerprint} (SHA-256, hex) and {@code tls.client.not_after}.
 */
public final class ClientCertificates {

    public static final String ATTRIBUTE = "portica.tls.clientCertificate";

    private ClientCertificates() {
    }

    static void publish(MessageContext ctx, SslInfo ssl) {
        X509Certificate[] chain = ssl == null ? null : ssl.getPeerCertificates();
        if (chain == null || chain.length == 0) {
            ctx.setVariable("tls.client.presented", false);
            return;
        }
        X509Certificate leaf = chain[0];
        ctx.attributes().put(ATTRIBUTE, leaf);
        ctx.setVariable("tls.client.presented", true);
        ctx.setVariable("tls.client.subject", leaf.getSubjectX500Principal().getName());
        ctx.setVariable("tls.client.issuer", leaf.getIssuerX500Principal().getName());
        ctx.setVariable("tls.client.fingerprint", Pem.fingerprint(leaf));
        ctx.setVariable("tls.client.not_after", leaf.getNotAfter().toInstant().toString());
    }

    public static X509Certificate of(MessageContext ctx) {
        return ctx.attributes().get(ATTRIBUTE) instanceof X509Certificate c ? c : null;
    }
}
