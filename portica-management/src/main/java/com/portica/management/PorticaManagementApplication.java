package com.portica.management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Portica management plane: the admin API behind the console and automation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PorticaManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(PorticaManagementApplication.class, args);
    }
}
