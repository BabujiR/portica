package com.portica.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Portica AI gateway: a provider-agnostic LLM endpoint with token quotas, cost tracking
 * and guardrails. Feature work lands in Phase 7.
 */
@SpringBootApplication
public class PorticaAiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaAiGatewayApplication.class, args);
    }
}
