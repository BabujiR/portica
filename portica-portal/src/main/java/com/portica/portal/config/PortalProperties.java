package com.portica.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Settings under {@code portica.portal}.
 *
 * @param managementUrl        the management API (internal portal endpoints)
 * @param gatewayUrl           the API gateway as this service reaches it, for try-it requests
 * @param allowedOrigins       browser origins of the portal UI (CORS)
 * @param requireVerifiedEmail refuse developers whose identity provider hasn't verified their e-mail address
 * @param auth                 client credentials of the portal's service account (realm role portica-portal)
 * @param tryIt                limits for try-it requests
 */
@ConfigurationProperties(prefix = "portica.portal")
public record PortalProperties(@DefaultValue("http://localhost:8080") String managementUrl,
                               @DefaultValue("http://localhost:8081") String gatewayUrl,
                               @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
                               @DefaultValue("true") boolean requireVerifiedEmail,
                               @DefaultValue Auth auth,
                               @DefaultValue TryIt tryIt) {

    public record Auth(String tokenUri, @DefaultValue("portica-portal") String clientId, String clientSecret,
                       @DefaultValue("10s") Duration timeout) {
    }

    /**
     * @param timeout          per request
     * @param maxBodyBytes     request and response bodies larger than this are refused or cut
     * @param requestsPerMinute per developer, so the portal can't be used as an open relay
     */
    public record TryIt(@DefaultValue("30s") Duration timeout, @DefaultValue("1048576") int maxBodyBytes,
                        @DefaultValue("60") int requestsPerMinute) {
    }
}
