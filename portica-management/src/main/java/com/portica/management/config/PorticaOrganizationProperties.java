package com.portica.management.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Organization settings under {@code portica.organizations.*}.
 *
 * @param defaultEnvironments environments created with every new organization unless the request opts out
 */
@ConfigurationProperties(prefix = "portica.organizations")
public record PorticaOrganizationProperties(List<String> defaultEnvironments) {

    public PorticaOrganizationProperties {
        defaultEnvironments = defaultEnvironments == null
                ? List.of("dev", "test", "prod")
                : List.copyOf(defaultEnvironments);
    }
}
