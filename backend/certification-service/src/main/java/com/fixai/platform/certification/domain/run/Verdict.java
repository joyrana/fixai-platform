package com.fixai.platform.certification.domain.run;

import java.util.Collection;

/**
 * Certification verdict, computed only from scenario statuses (never from AI output).
 */
public enum Verdict {
    PASSED,
    FAILED,
    INCONCLUSIVE;

    /**
     * FAILED if any mandatory scenario failed; INCONCLUSIVE if none failed but any mandatory scenario errored or was
     * cancelled; PASSED only if every mandatory scenario passed.
     */
    public static Verdict of(Collection<ScenarioResultSummary> results) {
        boolean anyFailed = results.stream().anyMatch(r -> r.mandatory() && r.status() == ScenarioStatus.FAILED);
        if (anyFailed) {
            return FAILED;
        }
        boolean allPassed = results.stream().filter(ScenarioResultSummary::mandatory)
                .allMatch(r -> r.status() == ScenarioStatus.PASSED);
        return allPassed && !results.isEmpty() ? PASSED : INCONCLUSIVE;
    }

    public record ScenarioResultSummary(boolean mandatory, ScenarioStatus status) {
    }
}
