package com.fixai.platform.certification.application.engine;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evaluation.ExpectationEvaluator;
import com.fixai.platform.certification.domain.evaluation.StepEvaluation;
import com.fixai.platform.certification.domain.evaluation.Variables;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.StepResult;
import com.fixai.platform.certification.domain.run.StepStatus;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.FixVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Re-evaluates a finished scenario execution offline, from persisted evidence and step windows only, and reports any
 * difference from the stored results. A consistent replay proves the verdict is a deterministic function of the
 * evidence.
 */
public final class ReplayVerifier {

    public record Report(boolean consistent, int stepsReevaluated, List<String> mismatches) {
        public Report {
            mismatches = List.copyOf(mismatches);
        }
    }

    public Report verify(
            Scenario scenario, FixVersion version, String idPrefix, List<StepResult> stored, List<EvidenceRecord> evidence,
            List<AssertionResult> storedProtocolChecks) {
        ExpectationEvaluator evaluator = new ExpectationEvaluator(version);
        Variables variables = new Variables(idPrefix, () -> "<now>");
        List<String> mismatches = new ArrayList<>();
        int reevaluated = 0;

        for (StepResult result : stored) {
            if (result.status() == StepStatus.NOT_EXECUTED || result.index() >= scenario.steps().size()) {
                continue;
            }
            Step step = scenario.steps().get(result.index());
            if (step instanceof Step.Expect expect && result.anchorOrdinal() != null) {
                StepEvaluation replayed = evaluator.evaluateExpect(expect, evidence, result.anchorOrdinal(),
                        result.windowEndOrdinal(), new HashSet<>(result.consumedOrdinals()), variables);
                compare(result, replayed, mismatches);
                reevaluated++;
            } else if (step instanceof Step.ExpectNone none && result.anchorOrdinal() != null) {
                StepEvaluation replayed = evaluator.evaluateExpectNone(none, evidence, result.anchorOrdinal(),
                        result.windowEndOrdinal(), new HashSet<>(result.consumedOrdinals()), variables);
                compare(result, replayed, mismatches);
                reevaluated++;
            }
            result.captures().forEach(variables::put);
        }

        List<AssertionResult> replayedChecks = new ProtocolChecks().check(scenario, evidence,
                storedProtocolChecks.stream().anyMatch(c -> ProtocolChecks.EVIDENCE_CAP.equals(c.subject()) && !c.passed()));
        if (!sameOutcomes(storedProtocolChecks, replayedChecks)) {
            mismatches.add("Protocol checks differ: stored " + summary(storedProtocolChecks) + " replayed " + summary(replayedChecks));
        }
        return new Report(mismatches.isEmpty(), reevaluated, mismatches);
    }

    private static void compare(StepResult stored, StepEvaluation replayed, List<String> mismatches) {
        boolean storedPassed = stored.status() == StepStatus.PASSED;
        String prefix = "Step " + (stored.index() + 1) + " (" + stored.description() + "): ";
        if (storedPassed != replayed.passed()) {
            mismatches.add(prefix + "stored " + stored.status() + " but replay " + (replayed.passed() ? "PASSED" : "FAILED"));
        }
        if (!Objects.equals(stored.matchedOrdinal(), replayed.matchedOrdinal())) {
            mismatches.add(prefix + "matched evidence #" + stored.matchedOrdinal() + " but replay matched #" + replayed.matchedOrdinal());
        }
        if (!sameOutcomes(stored.assertions(), replayed.assertions())) {
            mismatches.add(prefix + "assertion results differ");
        }
    }

    private static boolean sameOutcomes(List<AssertionResult> a, List<AssertionResult> b) {
        return summary(a).equals(summary(b));
    }

    private static List<String> summary(List<AssertionResult> results) {
        return results.stream().map(r -> r.subject() + "=" + r.passed() + ":" + r.actual()).sorted().toList();
    }
}
