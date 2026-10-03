package com.fixai.platform.certification.application.service;

import java.time.Duration;

/**
 * Engine settings, bound from configuration by the config layer.
 *
 * @param simulatorHost host of the synthetic counterparty used for SIMULATOR targets
 * @param simulatorPort FIX port of the synthetic counterparty
 * @param maxParallelScenarios per-run scenario concurrency for simulator targets
 * @param scenarioTimeout hard limit per scenario
 * @param defaultHeartbeatSeconds HeartBtInt used unless a scenario overrides it
 * @param reconnectIntervalSeconds initiator reconnect back-off
 * @param engineVersion recorded on every run
 */
public record CertificationSettings(
        String simulatorHost,
        int simulatorPort,
        int maxParallelScenarios,
        Duration scenarioTimeout,
        int defaultHeartbeatSeconds,
        int reconnectIntervalSeconds,
        String engineVersion) {
}
