package com.portica.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Portica analytics service: Kafka consumer writing to ClickHouse plus the analytics
 * query API used by console dashboards. Feature work lands in Phase 6.
 */
@SpringBootApplication
public class PorticaAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaAnalyticsApplication.class, args);
    }
}
