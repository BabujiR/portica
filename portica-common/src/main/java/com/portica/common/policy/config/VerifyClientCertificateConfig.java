package com.portica.common.policy.config;

import java.util.List;

/**
 * VerifyClientCertificate: authenticates the calling app by the TLS client certificate it presented to the
 * gateway (mutual TLS), looked up by SHA-256 fingerprint among the certificates registered on app keys.
 *
 * @param bindToApp when true, the certificate must belong to the app an earlier VerifyAPIKey or OAuthV2
 *                  step identified (certificate-bound credentials, as in RFC 8705); default false, the
 *                  certificate alone identifies the app
 */
public record VerifyClientCertificateConfig(Boolean bindToApp) implements PolicyConfig {

    public VerifyClientCertificateConfig {
        bindToApp = bindToApp != null && bindToApp;
    }

    @Override
    public List<String> validate() {
        return List.of();
    }
}
