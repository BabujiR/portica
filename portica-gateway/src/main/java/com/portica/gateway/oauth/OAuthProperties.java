package com.portica.gateway.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the gateway's OAuth 2.0 endpoints under {@code portica.gateway.oauth.*}.
 *
 * @param tokenTtl lifetime of issued access tokens (default 1 hour, between 1 minute and 1 day)
 */
@ConfigurationProperties(prefix = "portica.gateway.oauth")
public record OAuthProperties(Duration tokenTtl) {

    public OAuthProperties {
        tokenTtl = tokenTtl == null ? Duration.ofHours(1) : tokenTtl;
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("portica.gateway.oauth.token-ttl must be between 1 minute and 1 day");
        }
    }
}
