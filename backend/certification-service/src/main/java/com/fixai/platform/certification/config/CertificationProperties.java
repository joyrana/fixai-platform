package com.fixai.platform.certification.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param simulator synthetic counterparty location; {@code embedded=true} starts one in-process (local/dev only)
 * @param maxConcurrentRuns runs executing at once; further runs queue up to {@code runQueueCapacity}
 * @param maxParallelScenarios per-run concurrency for simulator targets
 * @param brokerServiceUrl base URL of broker-service; when blank, external targets are refused
 * @param workflowServiceUrl base URL of workflow-service for consuming approvals; when blank, external targets are refused
 */
@Validated
@ConfigurationProperties(prefix = "fixai.certification")
public record CertificationProperties(
        @NotNull Simulator simulator,
        @Min(1) @Max(32) int maxConcurrentRuns,
        @Min(1) @Max(1000) int runQueueCapacity,
        @Min(1) @Max(32) int maxParallelScenarios,
        @NotNull Duration scenarioTimeout,
        @Min(1) @Max(300) int defaultHeartbeatSeconds,
        @Min(1) @Max(60) int reconnectIntervalSeconds,
        String brokerServiceUrl,
        String workflowServiceUrl,
        @NotBlank String engineVersion) {

    public record Simulator(@NotBlank String host, @Min(1) @Max(65535) int port, boolean embedded) {
    }
}
