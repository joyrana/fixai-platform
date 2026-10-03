package com.fixai.platform.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.certification.adapter.out.catalogue.ClasspathScenarioCatalogue;
import com.fixai.platform.certification.adapter.out.fix.QuickFixTransport;
import com.fixai.platform.certification.application.engine.CancellationToken;
import com.fixai.platform.certification.application.engine.ExecutionTarget;
import com.fixai.platform.certification.application.engine.ReplayVerifier;
import com.fixai.platform.certification.application.engine.ScenarioExecutor;
import com.fixai.platform.certification.domain.run.ScenarioOutcome;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.StepStatus;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.fixcore.FixVersion;
import com.fixai.platform.simulator.engine.FixSimulator;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Runs the real catalogue through the real QuickFIX/J transport against the in-process simulator. Proves that every
 * scenario passes against a compliant counterparty and that each defect profile is detected by the scenarios that
 * target it.
 */
class CertificationEngineIntegrationTest {

    private static final AtomicInteger SESSION_COUNTER = new AtomicInteger();
    private static FixSimulator simulator;
    private static int port;
    private static ClasspathScenarioCatalogue catalogue;
    private static ScenarioExecutor executor;
    private static ExecutorService pool;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        simulator = new FixSimulator(port);
        simulator.start();
        catalogue = new ClasspathScenarioCatalogue("classpath*:scenarios/*.yaml", "classpath*:suites/*.yaml");
        executor = new ScenarioExecutor(() -> new QuickFixTransport(Clock.systemUTC(), new FixMessageRedactor()),
                Clock.systemUTC(), Duration.ofSeconds(60), 1);
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterAll
    static void stop() {
        pool.shutdownNow();
        simulator.stop();
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void everyScenarioPassesAgainstCompliantCounterparty(FixVersion version) throws Exception {
        Map<String, ScenarioOutcome> outcomes = run(version, SimulatorProfile.COMPLIANT,
                catalogue.suite("full-certification").orElseThrow().scenarioIds());

        Map<String, String> notPassed = outcomes.entrySet().stream()
                .filter(e -> e.getValue().status() != ScenarioStatus.PASSED)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().status() + ": " + e.getValue().failureSummary()));
        assertThat(notPassed).as("scenarios not passing on " + version).isEmpty();
        assertThat(outcomes).hasSize(24);
    }

    @Test
    void defectProfilesAreDetectedByTheScenariosThatTargetThem() throws Exception {
        assertDetected(SimulatorProfile.WRONG_EXEC_TYPE_ON_FILL, List.of("ORD-002"), List.of("ORD-001"));
        assertDetected(SimulatorProfile.MISSING_EXEC_ID, List.of("ORD-001", "ORD-002"), List.of("SES-002"));
        assertDetected(SimulatorProfile.DUPLICATE_EXEC_ID, List.of("ORD-002", "ORD-003"), List.of("ORD-001"));
        assertDetected(SimulatorProfile.INCORRECT_CUM_QTY, List.of("ORD-002", "ORD-003"), List.of("ORD-001"));
        assertDetected(SimulatorProfile.WRONG_AVG_PX, List.of("ORD-002"), List.of("ORD-004"));
        assertDetected(SimulatorProfile.NO_CANCEL_RESPONSE, List.of("ORD-004"), List.of("ORD-001", "NEG-001"));
        assertDetected(SimulatorProfile.ACCEPT_UNKNOWN_CANCEL, List.of("NEG-003"), List.of("ORD-004"));
        assertDetected(SimulatorProfile.MISSING_ORIG_CLORDID, List.of("ORD-004", "ORD-005"), List.of("ORD-002"));
        assertDetected(SimulatorProfile.ACCEPT_DUPLICATE_CLORDID, List.of("NEG-004"), List.of("ORD-001"));
        assertDetected(SimulatorProfile.REJECT_ALL_ORDERS, List.of("ORD-001", "NEG-001"), List.of("SES-002", "NEG-003"));
        assertDetected(SimulatorProfile.HEARTBEAT_WITHOUT_TEST_REQ_ID, List.of("SES-002"), List.of("ORD-001"));
        assertDetected(SimulatorProfile.GAP_FILL_WITHOUT_FLAG, List.of("SES-005"), List.of("SES-004"));
        assertDetected(SimulatorProfile.SLOW_ACK, List.of("ORD-001"), List.of("SES-002"));
    }

    @Test
    void finishedScenarioReplaysToTheSameVerdictFromEvidenceAlone() throws Exception {
        for (SimulatorProfile profile : List.of(SimulatorProfile.COMPLIANT, SimulatorProfile.INCORRECT_CUM_QTY)) {
            String prefix = "RPL-" + SESSION_COUNTER.incrementAndGet();
            Scenario scenario = catalogue.scenario("ORD-003").orElseThrow();
            ScenarioOutcome outcome = executor.execute(scenario, target(FixVersion.FIX44, profile, prefix), prefix, new CancellationToken());

            ReplayVerifier.Report report = new ReplayVerifier().verify(scenario, FixVersion.FIX44, prefix,
                    outcome.steps(), outcome.evidence(), outcome.protocolChecks());

            assertThat(report.mismatches()).isEmpty();
            assertThat(report.consistent()).isTrue();
            long executedExpectations = outcome.steps().stream()
                    .filter(s -> s.type().startsWith("expect") && s.status() != StepStatus.NOT_EXECUTED).count();
            assertThat(report.stepsReevaluated()).isEqualTo(executedExpectations).isPositive();
        }
    }

    @Test
    void evidenceIsRedactedAndHashed() throws Exception {
        String prefix = "EVD-" + SESSION_COUNTER.incrementAndGet();
        ScenarioOutcome outcome = executor.execute(catalogue.scenario("ORD-002").orElseThrow(),
                target(FixVersion.FIX44, SimulatorProfile.COMPLIANT, prefix), prefix, new CancellationToken());

        assertThat(outcome.evidence()).isNotEmpty();
        assertThat(outcome.evidence()).filteredOn(r -> r.message() != null).allSatisfy(r -> {
            assertThat(r.message().sha256()).hasSize(64);
            assertThat(r.message().rawRedacted()).doesNotContain("\u0001");
        });
        assertThat(outcome.evidence()).extracting(r -> r.ordinal())
                .isSortedAccordingTo(Long::compare).doesNotHaveDuplicates();
    }

    @Test
    void cancellationStopsScenarioWithoutVerdict() throws Exception {
        String prefix = "CXL-" + SESSION_COUNTER.incrementAndGet();
        CancellationToken token = new CancellationToken();
        token.cancel();

        ScenarioOutcome outcome = executor.execute(catalogue.scenario("ORD-001").orElseThrow(),
                target(FixVersion.FIX44, SimulatorProfile.COMPLIANT, prefix), prefix, token);

        assertThat(outcome.status()).isEqualTo(ScenarioStatus.CANCELLED);
    }

    private void assertDetected(SimulatorProfile profile, List<String> mustFail, List<String> mustPass) throws Exception {
        List<String> ids = new ArrayList<>(mustFail);
        ids.addAll(mustPass);
        Map<String, ScenarioOutcome> outcomes = run(FixVersion.FIX44, profile, ids);
        for (String id : mustFail) {
            assertThat(outcomes.get(id).status()).as(profile + " must fail " + id + ": " + outcomes.get(id).failureSummary())
                    .isEqualTo(ScenarioStatus.FAILED);
            assertThat(outcomes.get(id).failureSummary()).as(profile + " " + id).isNotBlank();
        }
        for (String id : mustPass) {
            assertThat(outcomes.get(id).status()).as(profile + " must pass " + id + ": " + outcomes.get(id).failureSummary())
                    .isEqualTo(ScenarioStatus.PASSED);
        }
    }

    private Map<String, ScenarioOutcome> run(FixVersion version, SimulatorProfile profile, List<String> ids) throws Exception {
        List<Future<ScenarioOutcome>> futures = new ArrayList<>();
        for (String id : ids) {
            Scenario scenario = catalogue.scenario(id).orElseThrow();
            String prefix = "IT" + SESSION_COUNTER.incrementAndGet();
            futures.add(pool.submit(() -> executor.execute(scenario, target(version, profile, prefix), prefix, new CancellationToken())));
        }
        Map<String, ScenarioOutcome> outcomes = new java.util.LinkedHashMap<>();
        for (Future<ScenarioOutcome> future : futures) {
            ScenarioOutcome outcome = future.get();
            outcomes.put(outcome.scenarioId(), outcome);
        }
        return outcomes;
    }

    private static ExecutionTarget target(FixVersion version, SimulatorProfile profile, String senderCompId) {
        return new ExecutionTarget(version, "127.0.0.1", port, senderCompId, profile.compId(), 30);
    }
}
