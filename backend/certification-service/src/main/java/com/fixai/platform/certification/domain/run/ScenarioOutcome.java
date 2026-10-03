package com.fixai.platform.certification.domain.run;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import java.time.Instant;
import java.util.List;

/**
 * Complete result of one scenario execution.
 *
 * @param protocolChecks automatic checks applied to every scenario (session rejects, ExecID uniqueness, evidence cap)
 * @param failureSummary short factual summary of the first failure, or {@code null}
 */
public record ScenarioOutcome(
        String scenarioId,
        int scenarioVersion,
        ScenarioStatus status,
        List<StepResult> steps,
        List<AssertionResult> protocolChecks,
        List<EvidenceRecord> evidence,
        String senderCompId,
        String targetCompId,
        String failureSummary,
        Instant startedAt,
        Instant completedAt) {

    public ScenarioOutcome {
        steps = List.copyOf(steps);
        protocolChecks = List.copyOf(protocolChecks);
        evidence = List.copyOf(evidence);
    }
}
