package com.fixai.platform.certification.domain.run;

/**
 * PASSED/FAILED are verdicts from executable assertions; ERROR means the scenario could not be evaluated
 * (infrastructure or configuration problem) and therefore yields no verdict.
 */
public enum ScenarioStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    ERROR,
    CANCELLED
}
