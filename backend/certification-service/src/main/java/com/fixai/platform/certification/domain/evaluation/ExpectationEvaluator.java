package com.fixai.platform.certification.domain.evaluation;

import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.scenario.FieldExpectation;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.FixMessageView;
import com.fixai.platform.fixcore.FixVersion;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure evaluation of expectations over an evidence window {@code [anchor, windowEnd)}.
 *
 * <p>The live runner and offline replay both call this class with the same inputs, which is what makes a verdict
 * reproducible from persisted evidence. Selection uses {@code match} fields; verification uses {@code assertions}
 * and {@code invariants}. The first unconsumed inbound message that matches is selected.
 */
public final class ExpectationEvaluator {

    private final FixVersion version;

    public ExpectationEvaluator(FixVersion version) {
        this.version = version;
    }

    public Optional<EvidenceRecord> findMatch(
            String msgType, Map<String, String> match, List<EvidenceRecord> log, long anchor, long windowEnd,
            Set<Long> consumed, Variables variables) {
        return findMatch(EvidenceRecord.Direction.INBOUND, msgType, match, log, anchor, windowEnd, consumed, variables);
    }

    public Optional<EvidenceRecord> findMatch(
            EvidenceRecord.Direction direction, String msgType, Map<String, String> match, List<EvidenceRecord> log,
            long anchor, long windowEnd, Set<Long> consumed, Variables variables) {
        Map<String, String> resolved = new HashMap<>();
        match.forEach((field, value) -> resolved.put(field, variables.resolve(value)));
        for (EvidenceRecord record : log) {
            if (record.ordinal() < anchor || record.ordinal() >= windowEnd) {
                continue;
            }
            if (record.kind() != EvidenceRecord.Kind.MESSAGE || record.direction() != direction
                    || consumed.contains(record.ordinal()) || !msgType.equals(record.msgType())) {
                continue;
            }
            if (resolved.entrySet().stream().allMatch(e -> record.message().value(e.getKey()).map(e.getValue()::equals).orElse(false))) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    public StepEvaluation evaluateExpect(
            Step.Expect step, List<EvidenceRecord> log, long anchor, long windowEnd, Set<Long> consumed, Variables variables) {
        Optional<EvidenceRecord> selected =
                findMatch(step.direction(), step.msgType(), step.match(), log, anchor, windowEnd, consumed, variables);
        if (selected.isEmpty()) {
            return new StepEvaluation(false, null, List.of(), Map.of(),
                    "No " + step.direction().name().toLowerCase() + " " + step.msgType() + describeMatch(step.match(), variables) + " within " + step.timeoutMillis() + " ms");
        }
        EvidenceRecord record = selected.get();
        FixMessageView message = record.message();
        List<AssertionResult> results = new ArrayList<>();
        step.assertions().forEach((field, expectation) ->
                results.add(check(field, expectation, message, record.ordinal(), variables)));
        for (String invariant : step.invariants()) {
            results.add(Invariant.parse(invariant).evaluate(message, record.ordinal()));
        }
        Map<String, String> captures = new HashMap<>();
        step.capture().forEach((variable, field) -> message.value(field).ifPresent(v -> captures.put(variable, v)));
        boolean passed = results.stream().allMatch(AssertionResult::passed);
        String detail = passed ? "Matched evidence #" + record.ordinal()
                : results.stream().filter(r -> !r.passed()).count() + " assertion(s) failed on evidence #" + record.ordinal();
        return new StepEvaluation(passed, record.ordinal(), results, captures, detail);
    }

    public StepEvaluation evaluateExpectNone(
            Step.ExpectNone step, List<EvidenceRecord> log, long anchor, long windowEnd, Set<Long> consumed, Variables variables) {
        Optional<EvidenceRecord> found = findMatch(step.msgType(), step.match(), log, anchor, windowEnd, consumed, variables);
        if (found.isPresent()) {
            AssertionResult result = new AssertionResult("no " + step.msgType(), "absent",
                    "received at evidence #" + found.get().ordinal(), false, found.get().ordinal());
            return new StepEvaluation(false, found.get().ordinal(), List.of(result), Map.of(),
                    "Unexpected inbound " + step.msgType() + describeMatch(step.match(), variables));
        }
        return new StepEvaluation(true, null, List.of(), Map.of(), "No matching message within " + step.windowMillis() + " ms");
    }

    AssertionResult check(String field, FieldExpectation expectation, FixMessageView message, long ordinal, Variables variables) {
        Optional<String> actual = message.value(field);
        String actualText = actual.orElse("<absent>");
        String expectedText = expectation.describe(version);
        boolean passed = switch (expectation.kind()) {
            case PRESENT -> actual.isPresent();
            case ABSENT -> actual.isEmpty();
            case EQUALS -> {
                String expected = variables.resolve(expectation.expectedFor(version));
                expectedText = "== " + expected;
                yield actual.map(expected::equals).orElse(false);
            }
            case NOT_EQUALS -> {
                String unexpected = variables.resolve(expectation.value());
                expectedText = "!= " + unexpected;
                yield actual.map(v -> !v.equals(unexpected)).orElse(false);
            }
            case ONE_OF -> actual.map(expectation.oneOf()::contains).orElse(false);
            case MATCHES -> actual.map(v -> Pattern.compile(expectation.pattern()).matcher(v).matches()).orElse(false);
            case NUMERIC -> actual.flatMap(ExpectationEvaluator::decimal)
                    .map(v -> compare(v, expectation.comparison(), expectation.number()))
                    .orElse(false);
        };
        return new AssertionResult(field, expectedText, actualText, passed, ordinal);
    }

    private static boolean compare(BigDecimal actual, FieldExpectation.Comparison comparison, BigDecimal expected) {
        int cmp = actual.compareTo(expected);
        return switch (comparison) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
        };
    }

    private static Optional<BigDecimal> decimal(String value) {
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String describeMatch(Map<String, String> match, Variables variables) {
        if (match.isEmpty()) {
            return "";
        }
        Map<String, String> resolved = new java.util.TreeMap<>();
        match.forEach((k, v) -> {
            try {
                resolved.put(k, variables.resolve(v));
            } catch (Variables.UnresolvedVariableException exception) {
                resolved.put(k, v);
            }
        });
        return " matching " + resolved;
    }
}
