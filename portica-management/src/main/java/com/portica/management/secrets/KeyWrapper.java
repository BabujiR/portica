package com.portica.management.secrets;

/**
 * Wraps and unwraps data keys with a key-encryption key (KEK). Implementations keep the KEK out of the
 * database: in configuration ({@link LocalKeyWrapper}) or in a key management service
 * ({@link VaultTransitKeyWrapper}). Cloud KMSs (AWS KMS, Google Cloud KMS, Azure Key Vault) fit the same
 * four operations.
 */
public interface KeyWrapper {

    /** Encrypts a data key; the result names the KEK (version) it used. */
    String wrap(byte[] dataKey);

    byte[] unwrap(String wrapped);

    /** True when {@code wrapped} uses the current KEK, so rotation can skip it. */
    boolean isCurrent(String wrapped);

    /** Re-encrypts a wrapped data key under the current KEK. */
    default String rewrap(String wrapped) {
        return wrap(unwrap(wrapped));
    }

    /** Creates a new KEK version and makes it current, when the backend manages keys itself. */
    void rotate();

    /** e.g. {@code local (active key k2)} or {@code vault transit/portica v3}. */
    String describe();
}
