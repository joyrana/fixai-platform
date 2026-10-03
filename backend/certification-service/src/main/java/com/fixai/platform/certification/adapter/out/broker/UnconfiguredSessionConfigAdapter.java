package com.fixai.platform.certification.adapter.out.broker;

import com.fixai.platform.certification.application.port.out.SessionConfigPort;
import com.fixai.platform.certification.application.service.CertificationExceptions;
import java.util.Optional;
import java.util.UUID;

/**
 * Used when no broker-service URL is configured: external (session-configuration) targets are refused, so only the
 * synthetic simulator can be certified against. This is the safe default.
 */
public class UnconfiguredSessionConfigAdapter implements SessionConfigPort {

    @Override
    public Optional<ResolvedSessionConfig> resolve(UUID sessionConfigId, String correlationId) {
        throw new CertificationExceptions.TargetNotAllowed(
                "External targets are disabled: no broker-service is configured (fixai.certification.broker-service-url)");
    }
}
