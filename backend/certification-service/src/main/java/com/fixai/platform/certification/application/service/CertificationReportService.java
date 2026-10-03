package com.fixai.platform.certification.application.service;

import com.fixai.platform.certification.application.port.out.RunRepository;
import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.StepResult;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Builds reports from persisted evidence only; it never infers or fills in results. */
public class CertificationReportService {

    private final CertificationRunService runService;
    private final RunRepository runs;
    private final Clock clock;

    public CertificationReportService(CertificationRunService runService, RunRepository runs, Clock clock) {
        this.runService = runService;
        this.runs = runs;
        this.clock = clock;
    }

    public CertificationReport build(UUID runId) {
        CertificationRun run = runService.get(runId);
        if (!run.status().isTerminal()) {
            throw new CertificationExceptions.InvalidState("Report is available once the run has finished (status " + run.status() + ")");
        }
        List<ScenarioExecution> executions = runs.scenarioExecutions(runId);
        List<CertificationReport.ScenarioSection> sections = new ArrayList<>();
        Map<String, int[]> categories = new TreeMap<>();
        for (ScenarioExecution execution : executions) {
            List<CertificationReport.FailedAssertion> failed = new ArrayList<>();
            for (StepResult step : runs.steps(execution.id())) {
                for (AssertionResult assertion : step.assertions()) {
                    if (!assertion.passed()) {
                        failed.add(new CertificationReport.FailedAssertion(step.index() + 1, assertion.subject(),
                                assertion.expected(), assertion.actual(), assertion.evidenceOrdinal()));
                    }
                }
            }
            List<CertificationReport.FailedAssertion> checks = execution.protocolChecks().stream()
                    .filter(c -> !c.passed())
                    .map(c -> new CertificationReport.FailedAssertion(null, c.subject(), c.expected(), c.actual(), c.evidenceOrdinal()))
                    .toList();
            sections.add(new CertificationReport.ScenarioSection(execution.id(), execution.scenarioId(),
                    execution.scenarioVersion(), execution.title(), execution.category(), execution.mandatory(),
                    execution.status().name(), execution.failureSummary(), failed, checks, execution.evidenceCount(),
                    Duration.between(execution.startedAt(), execution.completedAt()).toMillis()));
            int[] counts = categories.computeIfAbsent(execution.category(), k -> new int[4]);
            counts[0]++;
            if (execution.status() == ScenarioStatus.PASSED) {
                counts[1]++;
            } else if (execution.status() == ScenarioStatus.FAILED) {
                counts[2]++;
            } else {
                counts[3]++;
            }
        }
        Map<String, CertificationReport.CategoryCounts> byCategory = new TreeMap<>();
        categories.forEach((k, v) -> byCategory.put(k, new CertificationReport.CategoryCounts(v[0], v[1], v[2], v[3])));
        int passed = (int) executions.stream().filter(e -> e.status() == ScenarioStatus.PASSED).count();
        int failedCount = (int) executions.stream().filter(e -> e.status() == ScenarioStatus.FAILED).count();
        Long duration = run.startedAt() == null || run.completedAt() == null ? null
                : Duration.between(run.startedAt(), run.completedAt()).toMillis();
        return new CertificationReport(1, run.id(), clock.instant(), run.engineVersion(), run.catalogueHash(),
                run.evidenceDigest(),
                new CertificationReport.RunSection(run.status().name(), run.verdict() == null ? null : run.verdict().name(),
                        run.fixVersion().name(), run.target().type().name(), run.target().environment(),
                        run.target().simulatorProfile(), run.target().targetCompId(), run.suiteId(), run.requestedBy(),
                        run.correlationId(), run.createdAt(), run.startedAt(), run.completedAt(), duration),
                new CertificationReport.Summary(run.scenariosTotal(), passed, failedCount,
                        executions.size() - passed - failedCount, byCategory),
                sections, CertificationReport.DISCLAIMER);
    }
}
