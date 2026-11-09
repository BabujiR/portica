package com.portica.management.secrets;

/** A secret couldn't be encrypted or decrypted (backend unreachable, wrong key, tampered value). */
public class SecretsException extends IllegalStateException {

    public SecretsException(String message) {
        super(message);
    }

    public SecretsException(String message, Throwable cause) {
        super(message, cause);
    }
}
