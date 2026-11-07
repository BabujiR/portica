package com.portica.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Portica MCP gateway: generates MCP tools from API proxy OpenAPI specs and governs
 * calls to internal and external MCP servers. Feature work lands in Phase 7.
 */
@SpringBootApplication
public class PorticaMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaMcpApplication.class, args);
    }
}
