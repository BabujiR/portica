package com.portica.gateway.oauth;

import java.time.Instant;
import java.util.List;

/**
 * What an issued access token stands for. Stored under the token's SHA-256 hash, never the token.
 *
 * @param keyHash SHA-256 of the client's consumer key: the credential is looked up again on every
 *                use, so revoking the app, key or product stops its tokens at the next sync
 */
public record AccessTokenRecord(String organization, String environment, String credentialId, String keyHash,
                                String clientId, List<String> scopes, Instant issuedAt, Instant expiresAt) {

    public AccessTokenRecord {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
