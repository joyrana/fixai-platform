package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.RunTarget;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.certification.domain.run.StepResult;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.certification.domain.scenario.Suite;
import com.fixai.platform.fixcore.FixField;
import com.fixai.platform.fixcore.FixVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned (v1) API representations. Domain objects never leak directly to clients. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record StepView(String type, String description, String msgType) {
        static StepView of(Step step) {
            String type = step.getClass().getSimpleName();
            String msgType = switch (step) {
                case Step.Send s -> s.msgType();
                case Step.Expect e -> e.msgType();
                case Step.ExpectNone n -> n.msgType();
                default -> null;
            };
            return new StepView(Character.toLowerCase(type.charAt(0)) + type.substring(1), step.description(), msgType);
        }
    }

    public record ScenarioResponse(
            String id, int version, String title, String description, String category, List<FixVersion> fixVersions,
            List<String> tags, boolean mandatory, List<StepView> steps) {
        static ScenarioResponse of(Scenario s) {
            return new ScenarioResponse(s.id(), s.version(), s.title(), s.description(), s.category().name(),
                    s.fixVersions().stream().sorted().toList(), s.tags().stream().sorted().toList(), s.mandatory(),
                    s.steps().stream().map(StepView::of).toList());
        }
    }

    public record SuiteResponse(String id, String title, String description, List<String> scenarioIds) {
        static SuiteResponse of(Suite s) {
            return new SuiteResponse(s.id(), s.title(), s.description(), s.scenarioIds());
        }
    }

    /**
     * Run target. SESSION_CONFIG targets need {@code approvalId}: a START_EXTERNAL_CERTIFICATION approval whose payload
     * is exactly this session configuration, environment, FIX version and (sorted) scenario list. It is consumed once.
     */
    public record TargetRequest(
            RunTarget.Type type,
            @Pattern(regexp = "[A-Z_]{1,64}") String simulatorProfile,
            UUID sessionConfigId,
            UUID approvalId) {
    }

    public record StartRunRequest(
            @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String suiteId,
            @Size(max = 200) List<@Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String> scenarioIds,
            @NotNull FixVersion fixVersion,
            @Valid TargetRequest target) {
    }

    public record TestPlanRequest(
            @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String suiteId,
            @Size(max = 200) List<@Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String> scenarioIds,
            @NotNull FixVersion fixVersion) {
    }

    public record TestPlanValidation(boolean valid, List<String> problems, List<String> scenarioIds) {
    }

    public record RunResponse(
            UUID id, String status, String verdict, String suiteId, List<String> scenarioIds, FixVersion fixVersion,
            String targetType, String environment, String simulatorProfile, UUID sessionConfigId, String targetCompId,
            int scenariosTotal, int scenariosPassed, int scenariosFailed, int scenariosErrored, String errorDetail,
            String requestedBy, String correlationId, String catalogueHash, String engineVersion, String evidenceDigest,
            Instant createdAt, Instant startedAt, Instant completedAt) {
        static RunResponse of(CertificationRun r) {
            return new RunResponse(r.id(), r.status().name(), r.verdict() == null ? null : r.verdict().name(), r.suiteId(),
                    r.scenarioIds(), r.fixVersion(), r.target().type().name(), r.target().environment(),
                    r.target().simulatorProfile(), r.target().sessionConfigId(), r.target().targetCompId(),
                    r.scenariosTotal(), r.scenariosPassed(), r.scenariosFailed(), r.scenariosErrored(), r.errorDetail(),
                    r.requestedBy(), r.correlationId(), r.catalogueHash(), r.engineVersion(), r.evidenceDigest(),
                    r.createdAt(), r.startedAt(), r.completedAt());
        }
    }

    public record ScenarioExecutionResponse(
            UUID id, int position, String scenarioId, int scenarioVersion, String title, String category,
            boolean mandatory, String status, String senderCompId, String targetCompId, String failureSummary,
            List<AssertionResult> protocolChecks, long evidenceCount, Instant startedAt, Instant completedAt) {
        static ScenarioExecutionResponse of(ScenarioExecution e) {
            return new ScenarioExecutionResponse(e.id(), e.position(), e.scenarioId(), e.scenarioVersion(), e.title(),
                    e.category(), e.mandatory(), e.status().name(), e.senderCompId(), e.targetCompId(), e.failureSummary(),
                    e.protocolChecks(), e.evidenceCount(), e.startedAt(), e.completedAt());
        }
    }

    public record StepResponse(
            int index, String type, String description, String status, Long anchorOrdinal, Long windowEndOrdinal,
            Long matchedOrdinal, List<AssertionResult> assertions, Map<String, String> captures, String detail,
            Instant startedAt, Instant completedAt) {
        static StepResponse of(StepResult s) {
            return new StepResponse(s.index(), s.type(), s.description(), s.status().name(), s.anchorOrdinal(),
                    s.windowEndOrdinal(), s.matchedOrdinal(), s.assertions(), s.captures(), s.detail(), s.startedAt(),
                    s.completedAt());
        }
    }

    public record ScenarioExecutionDetail(ScenarioExecutionResponse execution, List<StepResponse> steps) {
    }

    public record EvidenceResponse(
            long ordinal, String kind, String direction, String msgType, Integer msgSeqNum, Instant occurredAt,
            String raw, List<FixField> fields, String sha256, String eventText) {
        static EvidenceResponse of(EvidenceRecord r) {
            return new EvidenceResponse(r.ordinal(), r.kind().name(), r.direction() == null ? null : r.direction().name(),
                    r.msgType(), r.message() == null ? null : r.message().msgSeqNum(), r.occurredAt(),
                    r.message() == null ? null : r.message().rawRedacted(),
                    r.message() == null ? List.of() : r.message().fields(),
                    r.message() == null ? null : r.message().sha256(), r.eventText());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
