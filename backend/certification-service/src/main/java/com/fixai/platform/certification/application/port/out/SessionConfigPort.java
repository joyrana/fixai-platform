package com.fixai.platform.certification.application.port.out;

import com.fixai.platform.fixcore.FixVersion;
import java.util.Optional;
import java.util.UUID;

/** Resolves an approved FIX session configuration owned by broker-service. */
public interface SessionConfigPort {

    Optional<ResolvedSessionConfig> resolve(UUID sessionConfigId, String correlationId);

    record ResolvedSessionConfig(
            UUID id, FixVersion fixVersion, String host, int port, String senderCompId, String targetCompId,
            String environment, String approvalStatus) {
    }
}
