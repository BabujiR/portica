package com.portica.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica runtime gateway. Stateless and horizontally scalable: it polls the management plane
 * for deployed API proxy revisions and serves them as Spring Cloud Gateway routes, updating its
 * route table in place when deployments change. Policies are enforced from Phase 3.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaGatewayApplication.class, args);
    }
}
