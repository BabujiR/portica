package com.portica.gateway.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the gateway's OAuth 2.0 endpoints under {@code portica.gateway.oauth.*}.
 *
 * @param tokenTtl                lifetime of issued access tokens (default 1 hour, between 1 minute and 1 day)
 * @param clientAttemptsPerMinute token and revocation requests per client id per minute, shared by all gateway
 *                                instances (default 30): bounds guessing a client's secret; 0 turns it off
 * @param ipAttemptsPerMinute     the same per client IP address (default 0, off: behind a load balancer every
 *                                request comes from its address)
 */
@ConfigurationProperties(prefix = "portica.gateway.oauth")
public record OAuthProperties(Duration tokenTtl, Integer clientAttemptsPerMinute, Integer ipAttemptsPerMinute) {

    public OAuthProperties {
        clientAttemptsPerMinute = clientAttemptsPerMinute == null ? 30 : Math.max(0, clientAttemptsPerMinute);
        ipAttemptsPerMinute = ipAttemptsPerMinute == null ? 0 : Math.max(0, ipAttemptsPerMinute);
        tokenTtl = tokenTtl == null ? Duration.ofHours(1) : tokenTtl;
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("portica.gateway.oauth.token-ttl must be between 1 minute and 1 day");
        }
    }
}
