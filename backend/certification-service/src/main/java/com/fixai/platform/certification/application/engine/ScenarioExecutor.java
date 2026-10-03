package com.fixai.platform.certification.application.engine;

import com.fixai.platform.certification.application.port.out.FixTransport;
import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evaluation.ExpectationEvaluator;
import com.fixai.platform.certification.domain.evaluation.StepEvaluation;
import com.fixai.platform.certification.domain.evaluation.Variables;
import com.fixai.platform.certification.domain.evidence.EvidenceLog;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.ScenarioOutcome;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.StepResult;
import com.fixai.platform.certification.domain.run.StepStatus;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.FixSessionSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executes one scenario against one target over a fresh {@link FixTransport}.
 *
 * <p>Execution stops at the first failed or errored step; later steps are reported as NOT_EXECUTED. Every wait is
 * bounded by the step timeout, the scenario deadline and the cancellation token. Pass/fail is decided exclusively by
 * {@link ExpectationEvaluator} and {@link ProtocolChecks}.
 */
public final class ScenarioExecutor {

    private static final DateTimeFormatter FIX_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final long POLL_NANOS = Duration.ofMillis(200).toNanos();

    private final FixTransport.Factory transports;
    private final Clock clock;
    private final ProtocolChecks protocolChecks;
    private final Duration scenarioTimeout;
    private final int reconnectIntervalSeconds;

    public ScenarioExecutor(
            FixTransport.Factory transports, Clock clock, Duration scenarioTimeout, int reconnectIntervalSeconds) {
        this.transports = transports;
        this.clock = clock;
        this.protocolChecks = new ProtocolChecks();
        this.scenarioTimeout = scenarioTimeout;
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
    }

    public ScenarioOutcome execute(Scenario scenario, ExecutionTarget target, String idPrefix, CancellationToken cancel) {
        Instant startedAt = clock.instant();
        long deadline = System.nanoTime() + scenarioTimeout.toNanos();
        EvidenceLog evidence = new EvidenceLog();
        Variables variables = new Variables(idPrefix, () -> FIX_TIME.format(clock.instant()));
        ExpectationEvaluator evaluator = new ExpectationEvaluator(target.version());
        Scenario.SessionOverrides overrides = scenario.session();
        String targetCompId = target.targetCompId()
                + (overrides.targetCompIdSuffix() == null ? "" : overrides.targetCompIdSuffix());
        FixSessionSpec spec = new FixSessionSpec(FixSessionSpec.Role.INITIATOR, target.version(), target.senderCompId(),
                targetCompId, target.host(), target.port(),
                overrides.heartbeatIntervalSeconds() == null ? target.defaultHeartbeatSeconds() : overrides.heartbeatIntervalSeconds(),
                reconnectIntervalSeconds, 10,
                overrides.resetOnLogon() == null || overrides.resetOnLogon(),
                false, false, true, FixSessionSpec.StoreType.MEMORY, null, null);

        List<StepResult> results = new ArrayList<>();
        Set<Long> consumed = new LinkedHashSet<>();
        long anchor = 0;
        boolean stopped = false;
        boolean cancelled = false;

        try (FixTransport transport = transports.create()) {
            transport.open(spec, evidence);
            List<Step> steps = scenario.steps();
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (stopped) {
                    results.add(StepResult.notExecuted(index, typeOf(step), step.description()));
                    continue;
                }
                if (cancel.isCancelled()) {
                    cancelled = true;
                    stopped = true;
                    results.add(StepResult.notExecuted(index, typeOf(step), step.description()));
                    continue;
                }
                Instant stepStart = clock.instant();
                StepResult result;
                try {
                    if (System.nanoTime() > deadline) {
                        result = error(index, step, stepStart, "Scenario timeout of " + scenarioTimeout.toSeconds() + " s exceeded");
                    } else {
                        StepRun run = runStep(index, step, transport, evidence, evaluator, variables, anchor, consumed, deadline, cancel, stepStart);
                        result = run.result();
                        anchor = run.nextAnchor();
                    }
                } catch (Variables.UnresolvedVariableException exception) {
                    // The linter guarantees an earlier step captures every variable, so an unresolved variable at
                    // run time means the counterparty omitted the field that should have been captured.
                    result = new StepResult(index, typeOf(step), step.description(), StepStatus.FAILED, anchor, null,
                            null, List.of(), List.of(), Map.of(),
                            exception.getMessage() + " (field absent in an earlier counterparty message)", stepStart, clock.instant());
                } catch (IllegalArgumentException exception) {
                    result = error(index, step, stepStart, "Scenario definition error: " + exception.getMessage());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    result = error(index, step, stepStart, "Interrupted");
                }
                results.add(result);
                if (result.status() != StepStatus.PASSED) {
                    stopped = true;
                }
                if (cancel.isCancelled()) {
                    cancelled = true;
                }
            }
        } catch (RuntimeException exception) {
            results.add(new StepResult(results.size(), "transport", "Open FIX session", StepStatus.ERROR, null, null, null,
                    List.of(), List.of(), Map.of(), "Transport failure: " + exception.getClass().getSimpleName(),
                    startedAt, clock.instant()));
        }

        List<EvidenceRecord> records = evidence.snapshot();
        List<AssertionResult> checks = protocolChecks.check(scenario, records, evidence.overflowed());
        ScenarioStatus status = status(results, checks, cancelled);
        return new ScenarioOutcome(scenario.id(), scenario.version(), status, results, checks, records,
                spec.senderCompId(), spec.targetCompId(), failureSummary(results, checks), startedAt, clock.instant());
    }

    private record StepRun(StepResult result, long nextAnchor) {
    }

    private StepRun runStep(
            int index, Step step, FixTransport transport, EvidenceLog evidence, ExpectationEvaluator evaluator,
            Variables variables, long anchor, Set<Long> consumed, long deadline, CancellationToken cancel, Instant stepStart)
            throws InterruptedException {
        return switch (step) {
            case Step.Logon logon -> {
                boolean ok = transport.awaitLogon(bounded(logon.timeoutMillis(), deadline));
                if (ok) {
                    yield new StepRun(passed(index, step, stepStart, anchor, evidence.size(), "Logged on"), anchor);
                }
                boolean anyInbound = evidence.snapshot().stream().anyMatch(EvidenceRecord::isInboundMessage);
                yield new StepRun(anyInbound
                        ? failed(index, step, stepStart, anchor, evidence.size(), "Counterparty responded but Logon did not complete within " + logon.timeoutMillis() + " ms")
                        : error(index, step, stepStart, "No response from counterparty within " + logon.timeoutMillis() + " ms (connectivity)"), anchor);
            }
            case Step.LogonRejected rejected -> {
                boolean refused = transport.awaitLogonRefused(bounded(rejected.timeoutMillis(), deadline));
                if (refused) {
                    yield new StepRun(passed(index, step, stepStart, anchor, evidence.size(), "Logon refused by counterparty"), evidence.size());
                }
                boolean loggedOn = transport.awaitLogon(Duration.ZERO);
                yield new StepRun(loggedOn
                        ? failed(index, step, stepStart, anchor, evidence.size(), "Counterparty accepted a Logon that must be refused")
                        : error(index, step, stepStart, "Counterparty neither refused nor accepted the Logon within " + rejected.timeoutMillis() + " ms"), anchor);
            }
            case Step.Send send -> {
                Map<String, Object> fields = resolveFields(send.fields(), variables);
                long sendAnchor = evidence.size();
                boolean sent = transport.send(send.msgType(), fields);
                yield new StepRun(sent
                        ? passed(index, step, stepStart, sendAnchor, evidence.size(), "Sent " + send.msgType())
                        : error(index, step, stepStart, "Session not logged on; " + send.msgType() + " not sent"), sendAnchor);
            }
            case Step.Expect expect -> {
                long windowEnd = awaitMatch(expect, evidence, evaluator, variables, anchor, consumed, deadline, cancel);
                List<EvidenceRecord> snapshot = evidence.snapshot();
                List<Long> consumedBefore = List.copyOf(consumed);
                StepEvaluation evaluation = evaluator.evaluateExpect(expect, snapshot, anchor, windowEnd, consumed, variables);
                if (evaluation.matchedOrdinal() != null) {
                    consumed.add(evaluation.matchedOrdinal());
                }
                evaluation.captures().forEach(variables::put);
                yield new StepRun(new StepResult(index, typeOf(step), step.description(),
                        evaluation.passed() ? StepStatus.PASSED : StepStatus.FAILED, anchor, windowEnd,
                        evaluation.matchedOrdinal(), consumedBefore, evaluation.assertions(), evaluation.captures(),
                        evaluation.detail(), stepStart, clock.instant()), anchor);
            }
            case Step.ExpectNone none -> {
                sleep(Math.min(none.windowMillis(), remainingMillis(deadline)), cancel);
                long windowEnd = evidence.size();
                List<Long> consumedBefore = List.copyOf(consumed);
                StepEvaluation evaluation = evaluator.evaluateExpectNone(none, evidence.snapshot(), anchor, windowEnd, consumed, variables);
                yield new StepRun(new StepResult(index, typeOf(step), step.description(),
                        evaluation.passed() ? StepStatus.PASSED : StepStatus.FAILED, anchor, windowEnd,
                        evaluation.matchedOrdinal(), consumedBefore, evaluation.assertions(), Map.of(), evaluation.detail(),
                        stepStart, clock.instant()), anchor);
            }
            case Step.SkipOutboundSequence skip -> {
                long skipAnchor = evidence.size();
                int first = transport.skipOutboundSequence(skip.count());
                Map<String, String> captures = Map.of();
                if (skip.captureAs() != null) {
                    variables.put(skip.captureAs(), Integer.toString(first));
                    captures = Map.of(skip.captureAs(), Integer.toString(first));
                }
                yield new StepRun(new StepResult(index, typeOf(step), step.description(), StepStatus.PASSED, skipAnchor,
                        evidence.size(), null, List.of(), List.of(), captures,
                        "Skipped " + skip.count() + " outbound sequence numbers from " + first, stepStart, clock.instant()), skipAnchor);
            }
            case Step.Logout logout -> {
                long logoutAnchor = evidence.size();
                transport.logout("Certification scenario logout");
                boolean ok = transport.awaitLogout(bounded(logout.timeoutMillis(), deadline));
                yield new StepRun(ok
                        ? passed(index, step, stepStart, logoutAnchor, evidence.size(), "Logout acknowledged")
                        : failed(index, step, stepStart, logoutAnchor, evidence.size(), "No Logout response within " + logout.timeoutMillis() + " ms"), logoutAnchor);
            }
            case Step.Disconnect disconnect -> {
                long disconnectAnchor = evidence.size();
                transport.disconnect("Certification scenario disconnect");
                yield new StepRun(passed(index, step, stepStart, disconnectAnchor, evidence.size(), "Disconnected"), disconnectAnchor);
            }
            case Step.Reconnect reconnect -> {
                boolean ok = transport.awaitLogon(bounded(reconnect.timeoutMillis(), deadline));
                yield new StepRun(ok
                        ? passed(index, step, stepStart, anchor, evidence.size(), "Reconnected and logged on")
                        : failed(index, step, stepStart, anchor, evidence.size(), "Logon after reconnect did not complete within " + reconnect.timeoutMillis() + " ms"), anchor);
            }
            case Step.Pause pause -> {
                long pauseAnchor = evidence.size();
                sleep(Math.min(pause.millis(), remainingMillis(deadline)), cancel);
                yield new StepRun(passed(index, step, stepStart, pauseAnchor, evidence.size(), "Paused " + pause.millis() + " ms"), pauseAnchor);
            }
        };
    }

    /** Waits for the first matching message; returns the exclusive window end that replay must reuse. */
    private long awaitMatch(
            Step.Expect expect, EvidenceLog evidence, ExpectationEvaluator evaluator, Variables variables, long anchor,
            Set<Long> consumed, long scenarioDeadline, CancellationToken cancel) throws InterruptedException {
        long deadline = Math.min(System.nanoTime() + Duration.ofMillis(expect.timeoutMillis()).toNanos(), scenarioDeadline);
        while (true) {
            List<EvidenceRecord> snapshot = evidence.snapshot();
            Optional<EvidenceRecord> match = evaluator.findMatch(expect.direction(),
                    expect.msgType(), expect.match(), snapshot, anchor, snapshot.size(), consumed, variables);
            if (match.isPresent()) {
                return match.get().ordinal() + 1;
            }
            if (System.nanoTime() >= deadline || cancel.isCancelled()) {
                return snapshot.size();
            }
            evidence.awaitGrowth(snapshot.size(), Math.min(deadline, System.nanoTime() + POLL_NANOS));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolveFields(Map<String, Object> fields, Variables variables) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            if (value instanceof List<?> group) {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (Object entry : group) {
                    entries.add(resolveFields((Map<String, Object>) entry, variables));
                }
                resolved.put(name, entries);
            } else {
                resolved.put(name, variables.resolve(String.valueOf(value)));
            }
        });
        return resolved;
    }

    private static ScenarioStatus status(List<StepResult> steps, List<AssertionResult> checks, boolean cancelled) {
        if (cancelled) {
            return ScenarioStatus.CANCELLED;
        }
        if (steps.stream().anyMatch(s -> s.status() == StepStatus.ERROR)) {
            return ScenarioStatus.ERROR;
        }
        if (steps.stream().anyMatch(s -> s.status() == StepStatus.FAILED) || checks.stream().anyMatch(c -> !c.passed())) {
            return ScenarioStatus.FAILED;
        }
        return ScenarioStatus.PASSED;
    }

    private static String failureSummary(List<StepResult> steps, List<AssertionResult> checks) {
        for (StepResult step : steps) {
            if (step.status() == StepStatus.FAILED || step.status() == StepStatus.ERROR) {
                String firstAssertion = step.assertions().stream().filter(a -> !a.passed()).findFirst()
                        .map(a -> "; " + a.subject() + " expected " + a.expected() + " but was " + a.actual())
                        .orElse("");
                return "Step " + (step.index() + 1) + " (" + step.description() + "): " + step.detail() + firstAssertion;
            }
        }
        return checks.stream().filter(c -> !c.passed()).findFirst()
                .map(c -> "Protocol check failed: " + c.subject() + " - " + c.actual())
                .orElse(null);
    }

    private StepResult passed(int index, Step step, Instant start, long anchor, long windowEnd, String detail) {
        return new StepResult(index, typeOf(step), step.description(), StepStatus.PASSED, anchor, windowEnd, null,
                List.of(), List.of(), Map.of(), detail, start, clock.instant());
    }

    private StepResult failed(int index, Step step, Instant start, long anchor, long windowEnd, String detail) {
        return new StepResult(index, typeOf(step), step.description(), StepStatus.FAILED, anchor, windowEnd, null,
                List.of(), List.of(), Map.of(), detail, start, clock.instant());
    }

    private StepResult error(int index, Step step, Instant start, String detail) {
        return new StepResult(index, typeOf(step), step.description(), StepStatus.ERROR, null, null, null, List.of(),
                List.of(), Map.of(), detail, start, clock.instant());
    }

    static String typeOf(Step step) {
        return switch (step) {
            case Step.Logon ignored -> "logon";
            case Step.LogonRejected ignored -> "logonRejected";
            case Step.Send ignored -> "send";
            case Step.Expect ignored -> "expect";
            case Step.ExpectNone ignored -> "expectNone";
            case Step.SkipOutboundSequence ignored -> "skipOutboundSequence";
            case Step.Logout ignored -> "logout";
            case Step.Disconnect ignored -> "disconnect";
            case Step.Reconnect ignored -> "reconnect";
            case Step.Pause ignored -> "pause";
        };
    }

    private static Duration bounded(long millis, long deadlineNanos) {
        return Duration.ofMillis(Math.min(millis, remainingMillis(deadlineNanos)));
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(0, Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis());
    }

    private static void sleep(long millis, CancellationToken cancel) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        while (System.nanoTime() < end && !cancel.isCancelled()) {
            Thread.sleep(Math.min(100, Math.max(1, Duration.ofNanos(end - System.nanoTime()).toMillis())));
        }
    }
}
