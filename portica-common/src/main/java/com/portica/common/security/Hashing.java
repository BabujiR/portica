package com.portica.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes for high-entropy secrets (API keys, client secrets, access tokens). These values are long
 * random strings, so a fast unsalted SHA-256 is enough to make a leaked hash useless; the gateway can
 * then look credentials up by hash without ever holding the plain values.
 */
public final class Hashing {

    private Hashing() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code value}. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Constant-time comparison of two hex hashes (null-safe). */
    public static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII),
                actualHex.getBytes(StandardCharsets.US_ASCII));
    }
}
