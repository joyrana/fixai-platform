package com.fixai.platform.certification.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Certification report (schemaVersion 1). Every statement is derived from persisted run data; failed assertions carry
 * the evidence ordinal they were evaluated on so a reviewer can inspect the exact message.
 */
public record CertificationReport(
        int schemaVersion,
        UUID runId,
        Instant generatedAt,
        String engineVersion,
        String catalogueHash,
        String evidenceDigest,
        RunSection run,
        Summary summary,
        List<ScenarioSection> scenarios,
        String disclaimer) {

    public static final String DISCLAIMER = "The verdict is computed solely from executable assertions over persisted "
            + "FIX message evidence. AI-generated analysis, where shown elsewhere, is advisory and does not affect the verdict.";

    public record RunSection(
            String status, String verdict, String fixVersion, String targetType, String environment,
            String simulatorProfile, String targetCompId, String suiteId, String requestedBy, String correlationId,
            Instant createdAt, Instant startedAt, Instant completedAt, Long durationMillis) {
    }

    public record Summary(int total, int passed, int failed, int errored, Map<String, CategoryCounts> byCategory) {
    }

    public record CategoryCounts(int total, int passed, int failed, int errored) {
    }

    public record ScenarioSection(
            UUID executionId, String scenarioId, int scenarioVersion, String title, String category, boolean mandatory,
            String status, String failureSummary, List<FailedAssertion> failedAssertions,
            List<FailedAssertion> failedProtocolChecks, long evidenceCount, long durationMillis) {
    }

    public record FailedAssertion(Integer step, String subject, String expected, String actual, Long evidenceOrdinal) {
    }
}
