package com.portica.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "portica.mcp.sync.enabled=false", "portica.mcp.analytics.enabled=false"})
class PorticaMcpApplicationTest {

    @Test
    void contextLoads() {
        // starts without the management plane or Kafka
    }
}
