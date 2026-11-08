package com.portica.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica AI gateway: one OpenAI-compatible API in front of several LLM providers (OpenAI and
 * OpenAI-compatible servers, Anthropic), with model routing and fallback, token quotas per app, cost
 * tracking and PII redaction. Clients reach it through the API gateway, which verifies their credentials.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaAiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaAiGatewayApplication.class, args);
    }
}
