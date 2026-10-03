package com.fixai.platform.certification.domain.run;

import com.fixai.platform.fixcore.FixVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Certification run aggregate as persisted. Counts and verdict are derived from scenario outcomes when the run
 * completes; they are never supplied by callers.
 */
public record CertificationRun(
        UUID id,
        String suiteId,
        List<String> scenarioIds,
        FixVersion fixVersion,
        RunTarget target,
        String senderCompId,
        RunStatus status,
        Verdict verdict,
        String requestedBy,
        String correlationId,
        String idempotencyKey,
        String requestHash,
        String catalogueHash,
        String engineVersion,
        String evidenceDigest,
        int scenariosTotal,
        int scenariosPassed,
        int scenariosFailed,
        int scenariosErrored,
        String errorDetail,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public CertificationRun {
        scenarioIds = List.copyOf(scenarioIds);
    }
}
