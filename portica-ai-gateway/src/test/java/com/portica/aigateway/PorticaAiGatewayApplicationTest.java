package com.portica.aigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.ai.sync.enabled=false", "portica.ai.quota-store=memory", "portica.ai.analytics.enabled=false"})
class PorticaAiGatewayApplicationTest {

    @Test
    void contextLoads() {
        // starts without the management plane, Redis or Kafka
    }
}
