package com.fixai.platform.simulator.web;

import com.fixai.platform.simulator.engine.FixSimulator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfiguration {

    @Bean
    public FixSimulator fixSimulator(SimulatorProperties properties) {
        return new FixSimulator(properties.fixPort());
    }

    @Bean
    public SmartLifecycle fixSimulatorLifecycle(FixSimulator simulator) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                simulator.start();
            }

            @Override
            public void stop() {
                simulator.stop();
            }

            @Override
            public boolean isRunning() {
                return simulator.isRunning();
            }
        };
    }

    /** Readiness: the acceptor is bound. Independent of whether any client is connected. */
    @Bean
    public HealthIndicator fixSimulatorHealth(FixSimulator simulator) {
        return () -> (simulator.isRunning() ? Health.up() : Health.down())
                .withDetail("fixPort", simulator.port())
                .withDetail("activeSessions", simulator.sessions().size())
                .build();
    }
}
