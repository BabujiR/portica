package com.portica.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"portica.gateway.sync.enabled=false", "portica.gateway.policy.store=memory",
        "portica.gateway.analytics.enabled=false"})
class PorticaGatewayApplicationTest {

    @Test
    void contextLoads() {
        // Verifies the gateway, its route locator and filters start without a management plane.
    }
}
