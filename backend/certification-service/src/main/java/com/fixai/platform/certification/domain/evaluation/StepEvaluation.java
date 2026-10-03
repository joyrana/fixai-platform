package com.fixai.platform.certification.domain.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Result of evaluating an expectation over a fixed evidence window.
 *
 * @param matchedOrdinal ordinal of the selected message, or {@code null} when nothing matched
 */
public record StepEvaluation(
        boolean passed, Long matchedOrdinal, List<AssertionResult> assertions, Map<String, String> captures, String detail) {

    public StepEvaluation {
        assertions = List.copyOf(assertions);
        captures = Map.copyOf(captures);
    }
}
