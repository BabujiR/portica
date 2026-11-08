package com.portica.management.secrets;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KEKs from configuration, by id. Wrapped form: {@code local:<keyId>:<base64(AES-GCM(kek, dataKey))>}.
 * Rotating means adding a new key, making it {@code active-key}, and letting the rotation job re-wrap;
 * old keys can be removed once {@code GET /platform/secrets} shows nothing uses them.
 */
public class LocalKeyWrapper implements KeyWrapper {

    private static final String AAD = "portica-data-key";

    private final Map<String, byte[]> keys;
    private final String active;

    public LocalKeyWrapper(Map<String, byte[]> keys, String active) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("No local keys");
        }
        if (!keys.containsKey(active)) {
            throw new IllegalStateException("portica.secrets.local.active-key '" + active + "' is not one of the configured keys "
                    + keys.keySet());
        }
        this.keys = new LinkedHashMap<>(keys);
        this.active = active;
    }

    public String activeKey() {
        return active;
    }

    @Override
    public String wrap(byte[] dataKey) {
        return "local:" + active + ":" + Base64.getEncoder().encodeToString(Aes.encrypt(keys.get(active), dataKey, AAD));
    }

    @Override
    public byte[] unwrap(String wrapped) {
        String[] parts = wrapped.split(":", 3);
        if (parts.length != 3 || !parts[0].equals("local")) {
            throw new SecretsException("Not a locally wrapped data key");
        }
        byte[] kek = keys.get(parts[1]);
        if (kek == null) {
            throw new SecretsException("Data key is wrapped with local key '" + parts[1] + "', which isn't configured");
        }
        return Aes.decrypt(kek, Base64.getDecoder().decode(parts[2].getBytes(StandardCharsets.US_ASCII)), AAD);
    }

    @Override
    public boolean isCurrent(String wrapped) {
        return wrapped.startsWith("local:" + active + ":");
    }

    @Override
    public void rotate() {
        throw new UnsupportedOperationException("Local keys rotate by configuration: add a key under portica.secrets.local.keys, "
                + "make it portica.secrets.local.active-key and restart; then re-wrap");
    }

    @Override
    public String describe() {
        return "local (active key " + active + ", " + keys.size() + " configured)";
    }
}
