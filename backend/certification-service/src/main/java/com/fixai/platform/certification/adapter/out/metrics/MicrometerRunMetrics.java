package com.fixai.platform.certification.adapter.out.metrics;

import com.fixai.platform.certification.application.port.out.RunMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Micrometer metrics with bounded tags (scenario IDs come from the registered catalogue, never from callers). Timers
 * publish histogram buckets so latency percentiles can be aggregated across instances in Prometheus.
 */
public class MicrometerRunMetrics implements RunMetrics {

    private final MeterRegistry registry;

    public MicrometerRunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void scenarioCompleted(String scenarioId, String status, Duration duration) {
        Timer.builder("fixai.certification.scenario.duration")
                .tag("scenario", scenarioId).tag("status", status)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(10)).maximumExpectedValue(Duration.ofMinutes(2))
                .register(registry).record(duration);
    }

    @Override
    public void runCompleted(String verdict, String targetType, Duration duration) {
        Timer.builder("fixai.certification.run.duration")
                .tag("verdict", verdict).tag("target", targetType)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(100)).maximumExpectedValue(Duration.ofMinutes(30))
                .register(registry).record(duration);
    }
}
