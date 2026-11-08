package com.portica.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica MCP gateway. Each MCP server defined in the management API is served at
 * {@code {gateway}/{org}/{env}/mcp/{name}}: the API gateway verifies the client's credential and
 * forwards here. Tools come from an API proxy's OpenAPI spec (each call goes back through the gateway,
 * so the proxy's policies and analytics apply) or from a remote MCP server Portica fronts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaMcpApplication.class, args);
    }
}
