package com.portica.management.secrets;

import com.portica.management.kvm.KvmCipher;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Envelope encryption with data keys wrapped by a real Vault Transit engine, and key rotation. */
@Testcontainers(disabledWithoutDocker = true)
class VaultTransitKeyWrapperTest {

    @Container
    static final VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.18")
            .withVaultToken("root-token")
            .withInitCommand("secrets enable transit", "write -f transit/keys/portica");

    private VaultTransitKeyWrapper wrapper() {
        return new VaultTransitKeyWrapper(new SecretsProperties.Vault(vault.getHttpHostAddress(), "root-token", null, null,
                "transit", "portica", 100, Duration.ofSeconds(10)));
    }

    @Test
    void encryptsThroughVaultAndSurvivesKeyRotation() {
        VaultTransitKeyWrapper wrapper = wrapper();
        KvmCipher cipher = new KvmCipher(wrapper, null);
        String stored = cipher.encrypt("sk-live-123", "ai-provider:acme:openai");
        assertThat(cipher.keyOf(stored)).isEqualTo("vault:v1");

        // a fresh instance (no cache) still decrypts: the data key comes back from Vault
        KvmCipher other = new KvmCipher(wrapper(), null);
        assertThat(other.decrypt(stored, "ai-provider:acme:openai")).isEqualTo("sk-live-123");

        wrapper.rotate();
        assertThat(cipher.needsRewrap(stored)).isTrue();
        String rewrapped = cipher.rewrap(stored);
        assertThat(cipher.keyOf(rewrapped)).isEqualTo("vault:v2");
        assertThat(new KvmCipher(wrapper(), null).decrypt(rewrapped, "ai-provider:acme:openai")).isEqualTo("sk-live-123");
        assertThat(cipher.needsRewrap(rewrapped)).isFalse();
        assertThat(wrapper.describe()).contains("transit/portica v2");
    }
}
