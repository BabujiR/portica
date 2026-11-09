package com.portica.management.kvm;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.management.secrets.LocalKeyWrapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KvmCipherTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER = "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=";
    private final KvmCipher cipher = new KvmCipher(KEY);

    @Test
    void roundTripsWithAFreshDataKeyEachTime() {
        String a = cipher.encrypt("s3cret ✓", "map-1/token");
        String b = cipher.encrypt("s3cret ✓", "map-1/token");

        assertThat(a).startsWith("s2:").isNotEqualTo(b).doesNotContain("s3cret");
        assertThat(cipher.decrypt(a, "map-1/token")).isEqualTo("s3cret ✓");
        assertThat(cipher.keyOf(a)).isEqualTo("local:default");
    }

    @Test
    void ciphertextIsBoundToItsRowAndKey() {
        String stored = cipher.encrypt("value", "map-1/token");
        assertThatThrownBy(() -> cipher.decrypt(stored, "map-1/other")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KvmCipher(OTHER).decrypt(stored, "map-1/token")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored.substring(0, stored.length() - 6) + "AAAAAA", "map-1/token"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("x1:garbage", "map-1/token")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAndRewrapsValuesWrittenBeforeEnvelopeEncryption() throws Exception {
        String legacy = legacyEncrypt("old secret", "ai-provider:x");
        assertThat(cipher.decrypt(legacy, "ai-provider:x")).isEqualTo("old secret");
        assertThat(cipher.needsRewrap(legacy)).isTrue();
        String rewrapped = cipher.rewrap(legacy);
        assertThat(rewrapped).startsWith("s2:");
        assertThat(cipher.decrypt(rewrapped, "ai-provider:x")).isEqualTo("old secret");
        assertThat(cipher.needsRewrap(rewrapped)).isFalse();
    }

    @Test
    void rotatingTheKeyEncryptionKeyOnlyRewrapsDataKeys() {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        keys.put("k1", Base64.getDecoder().decode(KEY));
        KvmCipher before = new KvmCipher(new LocalKeyWrapper(keys, "k1"), null);
        String stored = before.encrypt("api-key", "row-7");

        keys.put("k2", Base64.getDecoder().decode(OTHER));
        KvmCipher after = new KvmCipher(new LocalKeyWrapper(keys, "k2"), null);
        assertThat(after.decrypt(stored, "row-7")).as("old KEK still configured").isEqualTo("api-key");
        assertThat(after.needsRewrap(stored)).isTrue();
        String rewrapped = after.rewrap(stored);
        assertThat(after.keyOf(rewrapped)).isEqualTo("local:k2");
        assertThat(rewrapped.substring(rewrapped.indexOf('.'))).as("the encrypted value itself is unchanged")
                .isEqualTo(stored.substring(stored.indexOf('.')));

        KvmCipher onlyK2 = new KvmCipher(new LocalKeyWrapper(Map.of("k2", Base64.getDecoder().decode(OTHER)), "k2"), null);
        assertThat(onlyK2.decrypt(rewrapped, "row-7")).as("k1 can be retired").isEqualTo("api-key");
        assertThatThrownBy(() -> onlyK2.decrypt(stored, "row-7")).hasMessageContaining("k1");
    }

    @Test
    void withoutAKeySecretsAreDisabledAndBadKeysFailFast() {
        KvmCipher disabled = new KvmCipher("");
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x", "y")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> new KvmCipher("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KvmCipher("not base64!")).isInstanceOf(IllegalStateException.class);
    }

    /** The Phase 4 format: v1: + base64(IV + AES-GCM(kvm key, value)). */
    private static String legacyEncrypt(String value, String aad) throws Exception {
        byte[] iv = new byte[12];
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(ct, 0, all, 12, ct.length);
        return "v1:" + Base64.getEncoder().encodeToString(all);
    }
}
