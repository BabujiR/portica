package com.portica.management.kvm;

import com.portica.common.error.BusinessRuleViolationException;
import com.portica.management.secrets.KeyWrapper;
import com.portica.management.secrets.LocalKeyWrapper;
import com.portica.management.secrets.SecretsException;
import com.portica.management.secrets.SecretsProperties;
import com.portica.management.secrets.VaultTransitKeyWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encrypts every secret Portica stores (key value map entries, AI provider keys, MCP credentials and
 * tokens, target server TLS keys) with envelope encryption:
 * <ul>
 *   <li>each value gets its own random AES-256 data key and is encrypted with AES-256-GCM, bound to its
 *       row by associated data (so values can't be swapped between rows unnoticed);</li>
 *   <li>the data key is wrapped by a key-encryption key held locally or in Vault ({@link KeyWrapper}).</li>
 * </ul>
 * Stored format {@code s2:<base64url(wrapped data key)>.<base64(IV + ciphertext + tag)>}. Rotating the KEK
 * only re-wraps data keys ({@link #rewrap}); the encrypted values themselves don't change.
 * <p>
 * Values written before Phase 8 ({@code v1:...}, encrypted directly with {@code portica.kvm.encryption-key})
 * still decrypt, and {@link #rewrap} moves them to the envelope format with that key as their data key.
 */
@Component
public class KvmCipher {

    private static final Logger log = LoggerFactory.getLogger(KvmCipher.class);
    static final String LEGACY = "v1:";
    static final String ENVELOPE = "s2:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyWrapper wrapper;
    private final byte[] legacyKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public KvmCipher(SecretsProperties properties, @Value("${portica.kvm.encryption-key:}") String legacyBase64Key) {
        this.legacyKey = blank(legacyBase64Key) ? null : key(legacyBase64Key, "portica.kvm.encryption-key");
        this.wrapper = wrapper(properties, legacyKey);
        if (wrapper == null) {
            log.warn("No secrets key configured (portica.secrets.* or portica.kvm.encryption-key): encrypted key value maps, "
                    + "AI provider keys and MCP credentials can't be stored");
        } else {
            log.info("Secrets are encrypted with {}", wrapper.describe());
        }
    }

    /** Local keys only, from the legacy single key (tests, simple setups). */
    public KvmCipher(String legacyBase64Key) {
        this(new SecretsProperties("local", new SecretsProperties.Local(null, Map.of()),
                new SecretsProperties.Vault(null, null, null, null, "transit", "portica", 0, java.time.Duration.ofSeconds(10)),
                new SecretsProperties.Rotation(false, java.time.Duration.ofHours(6))), legacyBase64Key);
    }

    /** For tests and alternative backends. */
    public KvmCipher(KeyWrapper wrapper, byte[] legacyKey) {
        this.wrapper = wrapper;
        this.legacyKey = legacyKey;
    }

    private static KeyWrapper wrapper(SecretsProperties p, byte[] legacyKey) {
        if ("vault".equalsIgnoreCase(p.provider())) {
            return new VaultTransitKeyWrapper(p.vault());
        }
        if (!"local".equalsIgnoreCase(p.provider())) {
            throw new IllegalStateException("portica.secrets.provider must be local or vault, not " + p.provider());
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        p.local().keys().forEach((id, k) -> keys.put(id, key(k, "portica.secrets.local.keys." + id)));
        String active = p.local().activeKey();
        if (keys.isEmpty() && legacyKey != null) {
            keys.put("default", legacyKey);
            active = "default";
        }
        if (keys.isEmpty()) {
            return null;
        }
        if (blank(active)) {
            active = keys.size() == 1 ? keys.keySet().iterator().next() : null;
            if (active == null) {
                throw new IllegalStateException("Set portica.secrets.local.active-key: several local keys are configured");
            }
        }
        return new LocalKeyWrapper(keys, active);
    }

    public boolean enabled() {
        return wrapper != null;
    }

    public KeyWrapper wrapper() {
        return wrapper;
    }

    public String encrypt(String plaintext, String associatedData) {
        requireEnabled();
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        String wrapped = wrapper.wrap(dataKey);
        return ENVELOPE + Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped.getBytes(StandardCharsets.UTF_8))
                + "." + Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, dataKey, null,
                plaintext.getBytes(StandardCharsets.UTF_8), associatedData));
    }

    /**
     * @param associatedData binds the ciphertext to its row, so values can't be swapped between rows in the
     *                       database without detection
     */
    public String decrypt(String stored, String associatedData) {
        if (stored != null && stored.startsWith(LEGACY)) {
            if (legacyKey == null) {
                throw new SecretsException("This value was encrypted with portica.kvm.encryption-key, which isn't configured");
            }
            return new String(decryptPayload(legacyKey, stored.substring(LEGACY.length()), associatedData), StandardCharsets.UTF_8);
        }
        requireEnabled();
        Parts parts = parts(stored);
        return new String(decryptPayload(wrapper.unwrap(parts.wrapped()), parts.payload(), associatedData), StandardCharsets.UTF_8);
    }

    /**
     * The same secret with its data key wrapped by the current KEK; the value itself isn't decrypted.
     * Returns {@code stored} unchanged when it's already current.
     */
    public String rewrap(String stored) {
        requireEnabled();
        if (stored.startsWith(LEGACY)) {
            if (legacyKey == null) {
                throw new SecretsException("A v1 value needs portica.kvm.encryption-key to be re-wrapped");
            }
            return envelope(wrapper.wrap(legacyKey), stored.substring(LEGACY.length()));
        }
        Parts parts = parts(stored);
        if (wrapper.isCurrent(parts.wrapped())) {
            return stored;
        }
        return envelope(wrapper.rewrap(parts.wrapped()), parts.payload());
    }

    /** True when {@link #rewrap} would change the value. */
    public boolean needsRewrap(String stored) {
        return stored.startsWith(LEGACY) || !wrapper.isCurrent(parts(stored).wrapped());
    }

    /** Which KEK protects a value, e.g. {@code local:k1}, {@code vault:v2} or {@code legacy}. */
    public String keyOf(String stored) {
        if (stored.startsWith(LEGACY)) {
            return "legacy";
        }
        String wrapped = parts(stored).wrapped();
        String[] p = wrapped.split(":", 3);
        return p.length == 3 ? p[0] + ":" + p[1] : "unknown";
    }

    public void requireEnabled() {
        if (wrapper == null) {
            throw new BusinessRuleViolationException(
                    "Storing secrets needs a key: set portica.secrets (local keys or Vault) or portica.kvm.encryption-key");
        }
    }

    // ------------------------------------------------------------------ helpers

    private record Parts(String wrapped, String payload) {
    }

    private static Parts parts(String stored) {
        if (stored == null || !stored.startsWith(ENVELOPE) || stored.indexOf('.') < 0) {
            throw new SecretsException("Unknown encrypted value format");
        }
        int dot = stored.indexOf('.');
        try {
            String wrapped = new String(Base64.getUrlDecoder().decode(stored.substring(ENVELOPE.length(), dot)), StandardCharsets.UTF_8);
            return new Parts(wrapped, stored.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new SecretsException("Unknown encrypted value format", e);
        }
    }

    private static String envelope(String wrapped, String payload) {
        return ENVELOPE + Base64.getUrlEncoder().withoutPadding().encodeToString(wrapped.getBytes(StandardCharsets.UTF_8))
                + "." + payload;
    }

    private byte[] decryptPayload(byte[] key, String payload, String associatedData) {
        try {
            byte[] all = Base64.getDecoder().decode(payload);
            return aes(Cipher.DECRYPT_MODE, key, all, null, associatedData);
        } catch (IllegalArgumentException e) {
            throw new SecretsException("Could not decrypt a stored secret (malformed value)", e);
        }
    }

    /** Encrypt {@code plaintext}, or decrypt {@code all} (IV + ciphertext + tag). */
    private byte[] aes(int mode, byte[] key, byte[] all, byte[] plaintext, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (mode == Cipher.ENCRYPT_MODE) {
                byte[] iv = new byte[IV_BYTES];
                random.nextBytes(iv);
                cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
                byte[] ct = cipher.doFinal(plaintext);
                byte[] out = new byte[IV_BYTES + ct.length];
                System.arraycopy(iv, 0, out, 0, IV_BYTES);
                System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
                return out;
            }
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretsException("Could not decrypt a stored secret (wrong key or tampered value)", e);
        }
    }

    private static byte[] key(String base64, String name) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " must be base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(name + " must decode to 32 bytes (AES-256), got " + bytes.length);
        }
        return bytes;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
