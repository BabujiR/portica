package com.portica.management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Security settings under {@code portica.security.*}.
 *
 * @param platformAdminRole   Keycloak realm role that grants platform-wide administration
 * @param runtimeRole         Keycloak realm role of gateway service accounts (reads runtime configuration)
 * @param corsAllowedOrigins  browser origins allowed to call the API (the console)
 */
@ConfigurationProperties(prefix = "portica.security")
public record PorticaSecurityProperties(String platformAdminRole, String runtimeRole, List<String> corsAllowedOrigins) {

    /** Prefix applied to Keycloak realm roles when mapped to Spring Security authorities. */
    public static final String REALM_ROLE_PREFIX = "REALM_";

    public PorticaSecurityProperties {
        if (platformAdminRole == null || platformAdminRole.isBlank()) {
            platformAdminRole = "portica-platform-admin";
        }
        if (runtimeRole == null || runtimeRole.isBlank()) {
            runtimeRole = "portica-runtime";
        }
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    public String platformAdminAuthority() {
        return REALM_ROLE_PREFIX + platformAdminRole;
    }

    public String runtimeAuthority() {
        return REALM_ROLE_PREFIX + runtimeRole;
    }
}
