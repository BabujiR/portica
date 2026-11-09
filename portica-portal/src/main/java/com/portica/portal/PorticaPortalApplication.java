package com.portica.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica developer portal backend (BFF): public API and MCP tool catalogs with interactive docs,
 * try-it requests sent through the gateway, self-service app registration and usage dashboards. It
 * validates the developer's sign-in token and calls the management plane's internal portal API with its
 * own service credential, passing only the developer's verified e-mail address.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaPortalApplication.class, args);
    }
}
