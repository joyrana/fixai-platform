package com.fixai.platform.certification.application.service;

import com.fixai.platform.certification.application.engine.CancellationToken;
import com.fixai.platform.certification.application.engine.ExecutionTarget;
import com.fixai.platform.certification.application.engine.ReplayVerifier;
import com.fixai.platform.certification.application.engine.ScenarioExecutor;
import com.fixai.platform.certification.application.port.in.StartRunCommand;
import com.fixai.platform.certification.application.port.out.AuditPort;
import com.fixai.platform.certification.application.port.out.RunMetrics;
import com.fixai.platform.certification.application.port.out.RunRepository;
import com.fixai.platform.certification.application.port.out.ScenarioCatalogue;
import com.fixai.platform.certification.application.port.out.SessionConfigPort;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.RunStatus;
import com.fixai.platform.certification.domain.run.RunTarget;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.certification.domain.run.ScenarioOutcome;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.Verdict;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Suite;
import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates certification runs: validation, safety policy, idempotency, bounded execution, persistence, verdict and
 * audit. The verdict is computed exclusively from scenario statuses produced by executable assertions.
 */
public class CertificationRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CertificationRunService.class);
    private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("SIMULATOR", "TEST", "UAT");

    private final ScenarioCatalogue catalogue;
    private final RunRepository runs;
    private final ScenarioExecutor executor;
    private final SessionConfigPort sessionConfigs;
    private final AuditPort audit;
    private final RunMetrics metrics;
    private final CertificationSettings settings;
    private final Clock clock;
    private final ExecutorService runPool;
    private final ExecutorService scenarioPool;
    private final Map<UUID, CancellationToken> active = new ConcurrentHashMap<>();

    public CertificationRunService(
            ScenarioCatalogue catalogue, RunRepository runs, ScenarioExecutor executor, SessionConfigPort sessionConfigs,
            AuditPort audit, RunMetrics metrics, CertificationSettings settings, Clock clock, ExecutorService runPool,
            ExecutorService scenarioPool) {
        this.catalogue = catalogue;
        this.runs = runs;
        this.executor = executor;
        this.sessionConfigs = sessionConfigs;
        this.audit = audit;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
        this.runPool = runPool;
        this.scenarioPool = scenarioPool;
    }

    /** Result of a start request; {@code created} is false when an idempotent replay returned an existing run. */
    public record StartResult(CertificationRun run, boolean created) {
    }

    public StartResult start(StartRunCommand command, com.fixai.platform.web.Actor caller, String correlationId,
                             String idempotencyKey) {
        String actor = caller.id();
        if (caller.type() == com.fixai.platform.web.Actor.Type.AGENT && command.targetType() == RunTarget.Type.SESSION_CONFIG) {
            throw new CertificationExceptions.TargetNotAllowed(
                    "AI agents may only start simulated certifications; external targets require a human operator");
        }
        String requestHash = FixMessageRedactor.sha256(command.canonical());
        if (idempotencyKey != null) {
            Optional<CertificationRun> existing = runs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().requestHash().equals(requestHash)) {
                    throw new CertificationExceptions.IdempotencyConflict(existing.get().id());
                }
                return new StartResult(existing.get(), false);
            }
        }

        List<Scenario> scenarios = resolveScenarios(command);
        UUID runId = UUID.randomUUID();
        TargetResolution resolution = resolveTarget(command, correlationId);
        RunTarget target = resolution.target();
        String senderBase = resolution.senderCompId() != null
                ? resolution.senderCompId()
                : "FX" + runId.toString().replace("-", "").substring(0, 10);
        CertificationRun run = new CertificationRun(runId, command.suiteId(),
                scenarios.stream().map(Scenario::id).toList(), command.fixVersion(), target, senderBase, RunStatus.QUEUED,
                null, actor, correlationId, idempotencyKey, requestHash, catalogue.catalogueHash(),
                settings.engineVersion(), null, scenarios.size(), 0, 0, 0, null, clock.instant(), null, null);
        runs.create(run);
        audit.record(actor, "CERTIFICATION_RUN_REQUESTED", "certification-run", runId.toString(), correlationId,
                "ACCEPTED", Map.of("fixVersion", command.fixVersion().name(), "targetType", target.type().name(),
                        "environment", target.environment(), "scenarioCount", Integer.toString(scenarios.size())));

        CancellationToken token = new CancellationToken();
        active.put(runId, token);
        try {
            runPool.submit(() -> execute(run, scenarios, token));
        } catch (RejectedExecutionException exception) {
            active.remove(runId);
            runs.complete(runId, RunStatus.ERROR, Verdict.INCONCLUSIVE, 0, 0, 0, null, "Run capacity exhausted", clock.instant());
            throw new CertificationExceptions.InvalidState("Run capacity exhausted; retry later");
        }
        return new StartResult(run, true);
    }

    /** Validates a test plan without starting a run; returns the resolved scenario IDs. */
    public List<String> validatePlan(StartRunCommand command) {
        return resolveScenarios(command).stream().map(Scenario::id).toList();
    }

    public CertificationRun get(UUID runId) {
        return runs.find(runId).orElseThrow(() -> new CertificationExceptions.NotFound("Certification run", runId));
    }

    public List<CertificationRun> list(int page, int size) {
        return runs.list(page, size);
    }

    public long count() {
        return runs.count();
    }

    public List<ScenarioExecution> executions(UUID runId) {
        get(runId);
        return runs.scenarioExecutions(runId);
    }

    public CertificationRun cancel(UUID runId, com.fixai.platform.web.Actor caller, String correlationId) {
        String actor = caller.id();
        CertificationRun run = get(runId);
        if (run.status().isTerminal()) {
            throw new CertificationExceptions.InvalidState("Run is already " + run.status());
        }
        CancellationToken token = active.get(runId);
        if (token != null) {
            token.cancel();
        }
        audit.record(actor, "CERTIFICATION_RUN_CANCEL_REQUESTED", "certification-run", runId.toString(), correlationId,
                "ACCEPTED", Map.of());
        return get(runId);
    }

    /** Re-evaluates every scenario of a finished run from persisted evidence. */
    public ReplayReport replay(UUID runId, com.fixai.platform.web.Actor caller, String correlationId) {
        String actor = caller.id();
        CertificationRun run = get(runId);
        if (!run.status().isTerminal()) {
            throw new CertificationExceptions.InvalidState("Run is still " + run.status());
        }
        List<ScenarioReplay> results = new ArrayList<>();
        for (ScenarioExecution execution : runs.scenarioExecutions(runId)) {
            Optional<Scenario> scenario = catalogue.scenario(execution.scenarioId())
                    .filter(s -> s.version() == execution.scenarioVersion());
            if (scenario.isEmpty()) {
                results.add(new ScenarioReplay(execution.id(), execution.scenarioId(), false, 0,
                        List.of("Scenario " + execution.scenarioId() + "@" + execution.scenarioVersion()
                                + " is not in the current catalogue; replay requires the original definition")));
                continue;
            }
            ReplayVerifier.Report report = new ReplayVerifier().verify(scenario.get(), run.fixVersion(),
                    execution.idPrefix(), runs.steps(execution.id()), runs.evidence(execution.id()), execution.protocolChecks());
            results.add(new ScenarioReplay(execution.id(), execution.scenarioId(), report.consistent(),
                    report.stepsReevaluated(), report.mismatches()));
        }
        String digest = digest(runs.evidenceHashes(runId));
        boolean digestMatches = digest.equals(run.evidenceDigest());
        boolean consistent = digestMatches && results.stream().allMatch(ScenarioReplay::consistent);
        audit.record(actor, "CERTIFICATION_REPLAY_VERIFIED", "certification-run", runId.toString(), correlationId,
                consistent ? "CONSISTENT" : "INCONSISTENT", Map.of("scenarios", Integer.toString(results.size())));
        return new ReplayReport(runId, consistent, digestMatches, results);
    }

    public record ScenarioReplay(UUID executionId, String scenarioId, boolean consistent, int stepsReevaluated,
                                 List<String> mismatches) {
    }

    public record ReplayReport(UUID runId, boolean consistent, boolean evidenceDigestMatches, List<ScenarioReplay> scenarios) {
    }

    /** Marks runs orphaned by a previous process; they cannot be resumed mid-session because FIX state is lost. */
    public int recoverInterruptedRuns() {
        int count = runs.failInterruptedRuns("Interrupted by service restart; re-run required", clock.instant());
        if (count > 0) {
            LOGGER.warn("Marked {} interrupted certification run(s) as ERROR", count);
        }
        return count;
    }

    private void execute(CertificationRun run, List<Scenario> scenarios, CancellationToken token) {
        Instant started = clock.instant();
        try {
            if (!runs.markRunning(run.id(), started)) {
                return;
            }
            audit.record("certification-service", "CERTIFICATION_RUN_STARTED", "certification-run", run.id().toString(),
                    run.correlationId(), "STARTED", Map.of());
            int parallelism = run.target().allowsParallelScenarios() ? settings.maxParallelScenarios() : 1;
            Semaphore permits = new Semaphore(parallelism);
            List<Future<ScenarioStatus>> futures = new ArrayList<>();
            for (int position = 0; position < scenarios.size(); position++) {
                Scenario scenario = scenarios.get(position);
                int index = position;
                permits.acquire();
                if (token.isCancelled()) {
                    permits.release();
                    break;
                }
                futures.add(scenarioPool.submit(() -> {
                    try {
                        return runScenario(run, scenario, index, token);
                    } finally {
                        permits.release();
                    }
                }));
            }
            int passed = 0;
            int failed = 0;
            int errored = 0;
            for (Future<ScenarioStatus> future : futures) {
                ScenarioStatus status = future.get();
                switch (status) {
                    case PASSED -> passed++;
                    case FAILED -> failed++;
                    default -> errored++;
                }
            }
            List<Verdict.ScenarioResultSummary> summaries = runs.scenarioExecutions(run.id()).stream()
                    .map(e -> new Verdict.ScenarioResultSummary(e.mandatory(), e.status())).toList();
            boolean cancelled = token.isCancelled();
            boolean complete = summaries.size() == scenarios.size();
            Verdict verdict = cancelled || !complete ? Verdict.INCONCLUSIVE : Verdict.of(summaries);
            RunStatus status = cancelled ? RunStatus.CANCELLED : RunStatus.COMPLETED;
            String digest = digest(runs.evidenceHashes(run.id()));
            runs.complete(run.id(), status, verdict, passed, failed, errored, digest, null, clock.instant());
            metrics.runCompleted(verdict.name(), run.target().type().name(), Duration.between(started, clock.instant()));
            audit.record("certification-service", "CERTIFICATION_RUN_COMPLETED", "certification-run", run.id().toString(),
                    run.correlationId(), verdict.name(), Map.of("passed", Integer.toString(passed),
                            "failed", Integer.toString(failed), "errored", Integer.toString(errored),
                            "evidenceDigest", digest));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(run, "Run interrupted");
        } catch (ExecutionException | RuntimeException exception) {
            LOGGER.error("Certification run {} failed", run.id(), exception);
            fail(run, "Engine error: " + exception.getClass().getSimpleName());
        } finally {
            active.remove(run.id());
        }
    }

    private ScenarioStatus runScenario(CertificationRun run, Scenario scenario, int position, CancellationToken token) {
        String idPrefix = run.target().type() == RunTarget.Type.SIMULATOR
                ? run.senderCompId() + "-" + String.format("%02d", position)
                : "FX" + run.id().toString().replace("-", "").substring(0, 10) + "-" + String.format("%02d", position);
        String sender = run.target().type() == RunTarget.Type.SIMULATOR ? idPrefix : run.senderCompId();
        ExecutionTarget target = new ExecutionTarget(run.fixVersion(), run.target().host(), run.target().port(), sender,
                run.target().targetCompId(), settings.defaultHeartbeatSeconds());
        ScenarioOutcome outcome = executor.execute(scenario, target, idPrefix, token);
        runs.saveScenarioOutcome(run.id(), position, scenario, idPrefix, outcome);
        metrics.scenarioCompleted(scenario.id(), outcome.status().name(),
                Duration.between(outcome.startedAt(), outcome.completedAt()));
        return outcome.status();
    }

    private void fail(CertificationRun run, String reason) {
        runs.complete(run.id(), RunStatus.ERROR, Verdict.INCONCLUSIVE, 0, 0, 0, null, reason, clock.instant());
        audit.record("certification-service", "CERTIFICATION_RUN_COMPLETED", "certification-run", run.id().toString(),
                run.correlationId(), "ERROR", Map.of("reason", reason));
    }

    private List<Scenario> resolveScenarios(StartRunCommand command) {
        List<String> problems = new ArrayList<>();
        if (command.fixVersion() == null) {
            problems.add("fixVersion is required");
        }
        boolean hasSuite = command.suiteId() != null && !command.suiteId().isBlank();
        if (hasSuite == !command.scenarioIds().isEmpty()) {
            problems.add("Exactly one of suiteId or scenarioIds must be provided");
        }
        if (!problems.isEmpty()) {
            throw new CertificationExceptions.InvalidRequest(problems);
        }
        List<String> ids = hasSuite
                ? catalogue.suite(command.suiteId()).map(Suite::scenarioIds)
                        .orElseThrow(() -> new CertificationExceptions.InvalidRequest(List.of("Unknown suite " + command.suiteId())))
                : command.scenarioIds();
        if (ids.size() > 200) {
            throw new CertificationExceptions.InvalidRequest(List.of("At most 200 scenarios per run"));
        }
        List<Scenario> scenarios = new ArrayList<>();
        for (String id : ids) {
            Optional<Scenario> scenario = catalogue.scenario(id);
            if (scenario.isEmpty()) {
                problems.add("Unknown scenario " + id);
            } else if (!scenario.get().supports(command.fixVersion())) {
                problems.add("Scenario " + id + " does not support " + command.fixVersion());
            } else {
                scenarios.add(scenario.get());
            }
        }
        if (!problems.isEmpty()) {
            throw new CertificationExceptions.InvalidRequest(problems);
        }
        return scenarios;
    }

    /** Resolved target; {@code senderCompId} is null for simulator targets, which use run-scoped CompIDs. */
    private record TargetResolution(RunTarget target, String senderCompId) {
    }

    private TargetResolution resolveTarget(StartRunCommand command, String correlationId) {
        RunTarget.Type type = command.targetType() == null ? RunTarget.Type.SIMULATOR : command.targetType();
        if (type == RunTarget.Type.SIMULATOR) {
            String profileName = command.simulatorProfile() == null ? SimulatorProfile.COMPLIANT.name() : command.simulatorProfile();
            SimulatorProfile profile;
            try {
                profile = SimulatorProfile.valueOf(profileName);
            } catch (IllegalArgumentException exception) {
                throw new CertificationExceptions.InvalidRequest(List.of("Unknown simulator profile " + profileName));
            }
            return new TargetResolution(new RunTarget(type, settings.simulatorHost(), settings.simulatorPort(),
                    profile.compId(), profile.name(), null, "SIMULATOR"), null);
        }
        if (command.sessionConfigId() == null) {
            throw new CertificationExceptions.InvalidRequest(List.of("sessionConfigId is required for SESSION_CONFIG targets"));
        }
        SessionConfigPort.ResolvedSessionConfig config = sessionConfigs.resolve(command.sessionConfigId(), correlationId)
                .orElseThrow(() -> new CertificationExceptions.NotFound("Session configuration", command.sessionConfigId()));
        if (!ALLOWED_ENVIRONMENTS.contains(config.environment())) {
            throw new CertificationExceptions.TargetNotAllowed(
                    "Certification against environment " + config.environment() + " is blocked by policy");
        }
        if (!"APPROVED".equals(config.approvalStatus())) {
            throw new CertificationExceptions.TargetNotAllowed("Session configuration is not approved");
        }
        if (config.fixVersion() != command.fixVersion()) {
            throw new CertificationExceptions.InvalidRequest(List.of(
                    "Run fixVersion " + command.fixVersion() + " does not match session configuration " + config.fixVersion()));
        }
        return new TargetResolution(new RunTarget(RunTarget.Type.SESSION_CONFIG, config.host(), config.port(),
                config.targetCompId(), null, config.id(), config.environment()), config.senderCompId());
    }

    static String digest(List<String> hashes) {
        return FixMessageRedactor.sha256(String.join("\n", hashes));
    }
}
