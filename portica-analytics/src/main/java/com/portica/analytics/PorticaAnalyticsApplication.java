package com.portica.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica analytics service: consumes the gateway's analytics events and trace transactions from
 * Kafka and writes them to ClickHouse. The query API that dashboards use lives in the management
 * service, which already handles authentication and organization permissions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaAnalyticsApplication.class, args);
    }
}
