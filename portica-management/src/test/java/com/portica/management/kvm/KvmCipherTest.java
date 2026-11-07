package com.portica.management.kvm;

import com.portica.common.error.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KvmCipherTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private final KvmCipher cipher = new KvmCipher(KEY);

    @Test
    void roundTripsWithAFreshIvEachTime() {
        String a = cipher.encrypt("s3cret ✓", "map-1/token");
        String b = cipher.encrypt("s3cret ✓", "map-1/token");

        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("s3cret");
        assertThat(cipher.decrypt(a, "map-1/token")).isEqualTo("s3cret ✓");
    }

    @Test
    void ciphertextIsBoundToItsEntryAndKey() {
        String stored = cipher.encrypt("value", "map-1/token");
        assertThatThrownBy(() -> cipher.decrypt(stored, "map-1/other")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KvmCipher("ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=").decrypt(stored, "map-1/token"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:" + stored.substring(3, 10) + "AAAA", "map-1/token"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeyEncryptedMapsAreDisabledAndBadKeysFailFast() {
        KvmCipher disabled = new KvmCipher("");
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("x", "y")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> new KvmCipher("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KvmCipher("not base64!")).isInstanceOf(IllegalStateException.class);
    }
}
