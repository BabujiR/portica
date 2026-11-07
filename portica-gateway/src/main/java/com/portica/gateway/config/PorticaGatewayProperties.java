package com.portica.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Gateway settings under {@code portica.gateway.*}.
 *
 * @param managementUrl base URL of portica-management
 * @param environments  which environments this gateway serves: {@code "*"}, {@code "acme/*"} or {@code "acme/prod"}
 * @param sync          configuration sync settings
 * @param auth          OAuth2 client-credentials settings used to call the management plane
 */
@ConfigurationProperties(prefix = "portica.gateway")
public record PorticaGatewayProperties(String managementUrl, List<String> environments, Sync sync, Auth auth) {

    public PorticaGatewayProperties {
        managementUrl = managementUrl == null || managementUrl.isBlank() ? "http://localhost:8080" : stripSlash(managementUrl);
        environments = environments == null || environments.isEmpty() ? List.of("*") : List.copyOf(environments);
        sync = sync == null ? new Sync(true, null, null) : sync;
        auth = auth == null ? new Auth(null, null, null) : auth;
    }

    /**
     * @param enabled       turn polling off (tests, or when config is pushed another way)
     * @param pollInterval  how often the cheap version index is polled
     * @param timeout       timeout for each call to the management plane
     */
    public record Sync(@DefaultValue("true") boolean enabled, Duration pollInterval, Duration timeout) {

        public Sync {
            pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /**
     * Keycloak client credentials of this gateway (a confidential client whose service account
     * has the {@code portica-runtime} realm role).
     */
    public record Auth(String tokenUri, String clientId, String clientSecret) {

        public boolean configured() {
            return tokenUri != null && !tokenUri.isBlank() && clientId != null && !clientId.isBlank();
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
