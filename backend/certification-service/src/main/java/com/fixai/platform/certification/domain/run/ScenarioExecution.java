package com.fixai.platform.certification.domain.run;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted scenario execution summary within a run. */
public record ScenarioExecution(
        UUID id,
        UUID runId,
        int position,
        String scenarioId,
        int scenarioVersion,
        String title,
        String category,
        boolean mandatory,
        ScenarioStatus status,
        String senderCompId,
        String targetCompId,
        String idPrefix,
        String failureSummary,
        List<AssertionResult> protocolChecks,
        long evidenceCount,
        Instant startedAt,
        Instant completedAt) {

    public ScenarioExecution {
        protocolChecks = List.copyOf(protocolChecks);
    }
}
