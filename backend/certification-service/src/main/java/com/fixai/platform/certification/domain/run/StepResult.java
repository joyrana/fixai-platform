package com.fixai.platform.certification.domain.run;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persisted outcome of one step, including the evidence window needed for offline replay.
 *
 * @param anchorOrdinal first evidence ordinal the step could consider
 * @param windowEndOrdinal exclusive end of the evaluated evidence window
 * @param consumedOrdinals evidence already claimed by earlier expectations (replay input)
 */
public record StepResult(
        int index,
        String type,
        String description,
        StepStatus status,
        Long anchorOrdinal,
        Long windowEndOrdinal,
        Long matchedOrdinal,
        List<Long> consumedOrdinals,
        List<AssertionResult> assertions,
        Map<String, String> captures,
        String detail,
        Instant startedAt,
        Instant completedAt) {

    public StepResult {
        consumedOrdinals = consumedOrdinals == null ? List.of() : List.copyOf(consumedOrdinals);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        captures = captures == null ? Map.of() : Map.copyOf(captures);
    }

    public static StepResult notExecuted(int index, String type, String description) {
        return new StepResult(index, type, description, StepStatus.NOT_EXECUTED, null, null, null, List.of(), List.of(),
                Map.of(), null, null, null);
    }
}
