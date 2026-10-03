package com.fixai.platform.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Standalone simulator process: FIX acceptor plus a small read-only admin API.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorServerApplication.class, args);
    }
}
