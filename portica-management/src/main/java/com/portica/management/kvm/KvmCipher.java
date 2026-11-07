package com.portica.management.kvm;

import com.portica.common.error.BusinessRuleViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption of encrypted key value map entries. Stored format:
 * {@code v1:} + base64(12-byte IV + ciphertext + tag). The key comes from
 * {@code portica.kvm.encryption-key} (base64 of 32 random bytes, e.g. {@code openssl rand -base64 32});
 * without it, encrypted maps cannot be written.
 */
@Component
public class KvmCipher {

    private static final Logger log = LoggerFactory.getLogger(KvmCipher.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public KvmCipher(@Value("${portica.kvm.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            log.warn("portica.kvm.encryption-key is not set: encrypted key value maps are disabled");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("portica.kvm.encryption-key must be base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("portica.kvm.encryption-key must decode to 32 bytes (AES-256), got "
                    + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plaintext, String associatedData) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * @param associatedData binds the ciphertext to its map and key, so values can't be swapped
     *                       between entries in the database without detection
     */
    public String decrypt(String stored, String associatedData) {
        requireEnabled();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown encrypted value format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a key value map entry (wrong key or tampered value)", e);
        }
    }

    public void requireEnabled() {
        if (key == null) {
            throw new BusinessRuleViolationException(
                    "Encrypted key value maps need portica.kvm.encryption-key to be configured");
        }
    }
}
