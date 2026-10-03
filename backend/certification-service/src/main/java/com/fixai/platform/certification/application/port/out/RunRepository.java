package com.fixai.platform.certification.application.port.out;

import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.RunStatus;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.certification.domain.run.ScenarioOutcome;
import com.fixai.platform.certification.domain.run.StepResult;
import com.fixai.platform.certification.domain.run.Verdict;
import com.fixai.platform.certification.domain.scenario.Scenario;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for runs, scenario executions, step results and evidence. Evidence is append-only. */
public interface RunRepository {

    void create(CertificationRun run);

    Optional<CertificationRun> find(UUID runId);

    Optional<CertificationRun> findByIdempotencyKey(String idempotencyKey);

    List<CertificationRun> list(int page, int size);

    long count();

    boolean markRunning(UUID runId, Instant startedAt);

    /** Persists a finished scenario with all its steps and evidence atomically. */
    UUID saveScenarioOutcome(UUID runId, int position, Scenario scenario, String idPrefix, ScenarioOutcome outcome);

    void complete(UUID runId, RunStatus status, Verdict verdict, int passed, int failed, int errored,
                  String evidenceDigest, String errorDetail, Instant completedAt);

    List<ScenarioExecution> scenarioExecutions(UUID runId);

    Optional<ScenarioExecution> scenarioExecution(UUID executionId);

    List<StepResult> steps(UUID executionId);

    List<EvidenceRecord> evidence(UUID executionId);

    /** Evidence SHA-256 values for the whole run, ordered by scenario position then ordinal. */
    List<String> evidenceHashes(UUID runId);

    /** Marks runs left QUEUED or RUNNING by a previous process as ERROR; returns the number updated. */
    int failInterruptedRuns(String reason, Instant at);
}
