package com.fixai.platform.certification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evaluation.ExpectationEvaluator;
import com.fixai.platform.certification.domain.evaluation.Invariant;
import com.fixai.platform.certification.domain.evaluation.StepEvaluation;
import com.fixai.platform.certification.domain.evaluation.Variables;
import com.fixai.platform.certification.domain.evidence.EvidenceLog;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.Verdict;
import com.fixai.platform.certification.domain.scenario.FieldExpectation;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.DictionaryRegistry;
import com.fixai.platform.fixcore.FixMessageInspector;
import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.fixcore.FixMessageView;
import com.fixai.platform.fixcore.FixVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluationTest {

    private static final ExpectationEvaluator EVALUATOR = new ExpectationEvaluator(FixVersion.FIX44);

    @Test
    void invariantEvaluatesArithmeticOverFields() {
        FixMessageView fill = view("35=8|37=O1|17=E1|150=F|39=1|55=AAPL|54=1|38=100|14=40|151=60|6=10|");

        assertThat(Invariant.parse("CumQty + LeavesQty == OrderQty").evaluate(fill, 3L).passed()).isTrue();
        assertThat(Invariant.parse("CumQty < OrderQty").evaluate(fill, 3L).passed()).isTrue();
        assertThat(Invariant.parse("OrderQty - CumQty >= 61").evaluate(fill, 3L).passed()).isFalse();
        AssertionResult missing = Invariant.parse("LastPx > 0").evaluate(fill, 3L);
        assertThat(missing.passed()).isFalse();
        assertThat(missing.actual()).isEqualTo("LastPx missing");
    }

    @Test
    void invariantRejectsAnythingButSimpleArithmetic() {
        assertThatThrownBy(() -> Invariant.parse("CumQty")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invariant.parse("Runtime.exec() == 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invariant.parse("CumQty + == 1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void variablesResolveDeterministicIdsCapturedValuesAndFailFastOnUnknown() {
        Variables vars = new Variables("RUN1-03", () -> "20261003-10:00:00.000");
        vars.put("orderId", "SIM-O-7");

        assertThat(vars.resolve("${id:o1}")).isEqualTo("RUN1-03-o1");
        assertThat(vars.resolve("x-${var:orderId}-y")).isEqualTo("x-SIM-O-7-y");
        assertThat(vars.resolve("${now}")).isEqualTo("20261003-10:00:00.000");
        assertThat(vars.resolve("plain $ {text}")).isEqualTo("plain $ {text}");
        assertThatThrownBy(() -> vars.resolve("${var:missing}")).isInstanceOf(Variables.UnresolvedVariableException.class);
    }

    @Test
    void evaluatorSelectsFirstUnconsumedMatchInWindowAndChecksAssertions() {
        List<EvidenceRecord> log = log(
                "35=8|11=C1|37=O1|17=E1|150=0|39=0|55=AAPL|54=1|38=100|14=0|151=100|6=0|",
                "35=8|11=C1|37=O1|17=E2|150=F|39=2|55=AAPL|54=1|38=100|14=100|151=0|6=190|");
        Map<String, FieldExpectation> assertions = new LinkedHashMap<>();
        assertions.put("ExecType", FieldExpectation.equalsByVersion(Map.of(FixVersion.FIX42, "2", FixVersion.FIX44, "F")));
        assertions.put("AvgPx", FieldExpectation.numeric(FieldExpectation.Comparison.GT, BigDecimal.ZERO));
        assertions.put("ExecID", FieldExpectation.notEquals("E1"));
        Step.Expect expect = new Step.Expect("fill", EvidenceRecord.Direction.INBOUND, "8", 1000,
                Map.of("ClOrdID", "C1"), assertions, List.of("CumQty + LeavesQty == OrderQty"), Map.of("execId", "ExecID"));

        StepEvaluation firstUnconsumed = EVALUATOR.evaluateExpect(expect, log, 0, log.size(), Set.of(0L), vars());
        StepEvaluation wrongMessage = EVALUATOR.evaluateExpect(expect, log, 0, log.size(), Set.of(), vars());
        StepEvaluation outsideWindow = EVALUATOR.evaluateExpect(expect, log, 0, 1, Set.of(0L), vars());

        assertThat(firstUnconsumed.passed()).isTrue();
        assertThat(firstUnconsumed.matchedOrdinal()).isEqualTo(1L);
        assertThat(firstUnconsumed.captures()).containsEntry("execId", "E2");
        assertThat(wrongMessage.passed()).isFalse();
        assertThat(wrongMessage.assertions()).filteredOn(a -> !a.passed()).extracting(AssertionResult::subject)
                .containsExactlyInAnyOrder("ExecType", "AvgPx", "ExecID");
        assertThat(outsideWindow.passed()).isFalse();
        assertThat(outsideWindow.matchedOrdinal()).isNull();
        assertThat(outsideWindow.detail()).contains("No inbound 8").contains("ClOrdID=C1");
    }

    @Test
    void expectNoneFailsWhenAMatchingMessageArrives() {
        List<EvidenceRecord> log = log("35=8|11=C1|37=O1|17=E1|150=4|39=4|55=AAPL|54=1|14=0|151=0|6=0|");
        Step.ExpectNone none = new Step.ExpectNone("no cancel", "8", 1000, Map.of("ClOrdID", "C1"));

        assertThat(EVALUATOR.evaluateExpectNone(none, log, 0, 1, Set.of(), vars()).passed()).isFalse();
        assertThat(EVALUATOR.evaluateExpectNone(none, log, 1, 1, Set.of(), vars()).passed()).isTrue();
    }

    @Test
    void verdictIsDerivedOnlyFromMandatoryScenarioStatuses() {
        assertThat(Verdict.of(List.of(summary(true, ScenarioStatus.PASSED), summary(false, ScenarioStatus.FAILED))))
                .isEqualTo(Verdict.PASSED);
        assertThat(Verdict.of(List.of(summary(true, ScenarioStatus.PASSED), summary(true, ScenarioStatus.FAILED))))
                .isEqualTo(Verdict.FAILED);
        assertThat(Verdict.of(List.of(summary(true, ScenarioStatus.PASSED), summary(true, ScenarioStatus.ERROR))))
                .isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(Verdict.of(List.of(summary(true, ScenarioStatus.FAILED), summary(true, ScenarioStatus.ERROR))))
                .isEqualTo(Verdict.FAILED);
        assertThat(Verdict.of(List.of())).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void evidenceLogIsBoundedAndReportsOverflow() {
        EvidenceLog log = new EvidenceLog(2);
        log.event(Instant.EPOCH, "a");
        log.event(Instant.EPOCH, "b");
        log.event(Instant.EPOCH, "c");

        assertThat(log.size()).isEqualTo(2);
        assertThat(log.overflowed()).isTrue();
    }

    private static Verdict.ScenarioResultSummary summary(boolean mandatory, ScenarioStatus status) {
        return new Verdict.ScenarioResultSummary(mandatory, status);
    }

    private static Variables vars() {
        return new Variables("T", () -> "now");
    }

    private static List<EvidenceRecord> log(String... bodies) {
        List<EvidenceRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            records.add(new EvidenceRecord(i, EvidenceRecord.Kind.MESSAGE, EvidenceRecord.Direction.INBOUND,
                    Instant.EPOCH, view(bodies[i]), null));
        }
        return records;
    }

    private static FixMessageView view(String body) {
        String raw = FixMessageInspector.withComputedLengthAndChecksum(
                "8=FIX.4.4|9=0|" + body.replaceFirst("35=8\\|", "35=8|34=2|49=SIM|52=20261003-10:00:00.000|56=C|") + "10=000|");
        return FixMessageView.fromRaw(raw, DictionaryRegistry.shared().transport(FixVersion.FIX44),
                DictionaryRegistry.shared().application(FixVersion.FIX44), new FixMessageRedactor());
    }
}
