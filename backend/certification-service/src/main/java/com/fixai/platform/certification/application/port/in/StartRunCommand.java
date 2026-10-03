package com.fixai.platform.certification.application.port.in;

import com.fixai.platform.certification.domain.run.RunTarget;
import com.fixai.platform.fixcore.FixVersion;
import java.util.List;
import java.util.UUID;

/**
 * Request to start a certification run. Exactly one of {@code suiteId} or {@code scenarioIds} is set.
 *
 * @param targetType SIMULATOR (default, synthetic) or SESSION_CONFIG (approved TEST/UAT configuration)
 * @param simulatorProfile simulator behaviour profile for SIMULATOR targets (default COMPLIANT)
 * @param sessionConfigId broker-service session configuration for SESSION_CONFIG targets
 * @param approvalId human approval (START_EXTERNAL_CERTIFICATION) consumed before a SESSION_CONFIG run starts
 */
public record StartRunCommand(
        String suiteId,
        List<String> scenarioIds,
        FixVersion fixVersion,
        RunTarget.Type targetType,
        String simulatorProfile,
        UUID sessionConfigId,
        UUID approvalId) {

    public StartRunCommand {
        scenarioIds = scenarioIds == null ? List.of() : List.copyOf(scenarioIds);
    }

    /** Canonical form used for idempotency comparison. */
    public String canonical() {
        return String.join("|", String.valueOf(suiteId), String.join(",", scenarioIds), String.valueOf(fixVersion),
                String.valueOf(targetType), String.valueOf(simulatorProfile), String.valueOf(sessionConfigId),
                String.valueOf(approvalId));
    }
}
