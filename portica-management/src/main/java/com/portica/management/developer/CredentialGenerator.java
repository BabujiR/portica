package com.portica.management.developer;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Random consumer keys (32 base62 characters, ~190 bits) and secrets (48 characters, ~285 bits). */
@Component
public class CredentialGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String consumerKey() {
        return random(32);
    }

    public String consumerSecret() {
        return random(48);
    }

    private String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
