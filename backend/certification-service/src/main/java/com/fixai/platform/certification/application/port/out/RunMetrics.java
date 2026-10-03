package com.fixai.platform.certification.application.port.out;

import java.time.Duration;

/** Metrics port; implementations must use bounded tag cardinality (no run IDs in tags). */
public interface RunMetrics {

    void scenarioCompleted(String scenarioId, String status, Duration duration);

    void runCompleted(String verdict, String targetType, Duration duration);

    RunMetrics NOOP = new RunMetrics() {
        @Override
        public void scenarioCompleted(String scenarioId, String status, Duration duration) {
        }

        @Override
        public void runCompleted(String verdict, String targetType, Duration duration) {
        }
    };
}
