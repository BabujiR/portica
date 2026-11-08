package com.portica.common.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemTest {

    @Test
    void readsCertificatesAndComputesTheSha256Fingerprint() {
        X509Certificate certificate = Pem.certificates(read("tls/client.crt")).get(0);
        assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=app-shop");
        assertThat(Pem.fingerprint(certificate))
                .isEqualTo("5f6bacb2513fe4f9c1a7f6f289f352406c252e589c454f0a895cdcd83ffb2558");
        assertThat(Pem.certificates(read("tls/client.crt") + read("tls/ca.crt"))).hasSize(2);
        assertThat(Pem.fingerprint(Pem.certificates(Pem.encode(certificate)).get(0)))
                .as("encode round trip").isEqualTo(Pem.fingerprint(certificate));
    }

    @Test
    void checksThatAKeyBelongsToACertificate() {
        X509Certificate certificate = Pem.certificates(read("tls/client.crt")).get(0);
        PrivateKey own = Pem.privateKey(read("tls/client.key"));
        PrivateKey other = Pem.privateKey(read("tls/gateway-client.key"));
        assertThat(Pem.matches(own, certificate)).isTrue();
        assertThat(Pem.matches(other, certificate)).isFalse();
    }

    @Test
    void rejectsWhatIsNotPem() {
        assertThatThrownBy(() -> Pem.certificates("hello")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pem.privateKey(read("tls/client.crt"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pem.privateKey("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PKCS #8");
    }

    private static String read(String resource) {
        try (InputStream in = PemTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
