package com.fixai.platform.fixgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the FIX gateway module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FixGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixGatewayApplication.class, args);
    }
}
