package com.fixai.platform.certification.domain.evaluation;

/**
 * Outcome of one executable assertion. {@code evidenceOrdinal} points at the evidence the assertion was evaluated on.
 */
public record AssertionResult(String subject, String expected, String actual, boolean passed, Long evidenceOrdinal) {
}
