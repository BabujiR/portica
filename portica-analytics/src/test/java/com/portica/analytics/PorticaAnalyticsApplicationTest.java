package com.portica.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "portica.analytics.enabled=false")
class PorticaAnalyticsApplicationTest {

    @Test
    void contextLoads() {
        // the service starts without Kafka or ClickHouse when ingestion is off
    }
}
