package com.fixai.platform.certification.adapter.out.metrics;

import com.fixai.platform.certification.application.port.out.RunMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/** Micrometer metrics with bounded tags (scenario IDs come from the registered catalogue, never from callers). */
public class MicrometerRunMetrics implements RunMetrics {

    private final MeterRegistry registry;

    public MicrometerRunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void scenarioCompleted(String scenarioId, String status, Duration duration) {
        Timer.builder("fixai.certification.scenario.duration")
                .tag("scenario", scenarioId).tag("status", status)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    @Override
    public void runCompleted(String verdict, String targetType, Duration duration) {
        Timer.builder("fixai.certification.run.duration")
                .tag("verdict", verdict).tag("target", targetType)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }
}
