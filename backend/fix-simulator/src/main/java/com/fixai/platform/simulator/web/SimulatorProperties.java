package com.fixai.platform.simulator.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param fixPort TCP port the FIX acceptor listens on
 */
@Validated
@ConfigurationProperties(prefix = "fixai.simulator")
public record SimulatorProperties(@Min(1) @Max(65535) int fixPort) {
}
