package com.portica.management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Portica management plane: the admin API behind the console and automation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PorticaManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaManagementApplication.class, args);
    }
}
