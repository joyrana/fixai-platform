package com.fixai.platform.workflow.adapter.web;

import com.fixai.platform.workflow.application.port.ApprovalRepository;
import com.fixai.platform.workflow.domain.approval.ApprovalRequest;
import com.fixai.platform.workflow.domain.approval.Decision;
import com.fixai.platform.workflow.domain.approval.RiskLevel;
import com.fixai.platform.workflow.domain.audit.AuditEvent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record CreateApprovalRequest(
            @NotBlank @Pattern(regexp = "[A-Z_]{3,64}") String action,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,64}") String targetType,
            @NotBlank @Size(max = 128) String targetId,
            @NotBlank @Pattern(regexp = "[A-Z]{3,16}") String environment,
            @NotNull @Size(max = 64) Map<String, Object> arguments,
            @NotBlank @Size(min = 10, max = 4000) String justification,
            RiskLevel risk,
            @Size(max = 50) List<@Size(max = 256) String> evidenceRefs,
            @Size(max = 50) List<@Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String> traceIds,
            @Min(60) @Max(604800) Long ttlSeconds,
            @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String onBehalfOf) {
    }

    public record DecisionRequest(@NotNull Decision decision, @NotBlank @Size(min = 10, max = 4000) String rationale) {
    }

    public record CancelRequest(@Size(max = 1000) String reason) {
    }

    /** The action the executor is about to perform; re-hashed and compared with the approved payload. */
    public record ConsumeRequest(
            @NotBlank String action,
            @NotBlank String targetType,
            @NotBlank String targetId,
            @NotBlank String environment,
            @NotNull Map<String, Object> arguments) {
    }

    public record ApprovalResponse(
            UUID id, String action, String targetType, String targetId, String environment, Map<String, Object> arguments,
            String payloadHash, String justification, String requestedBy, String requesterType, String riskLevel,
            List<String> evidenceRefs, List<String> traceIds, String status, String policyVersion, Instant expiresAt,
            Instant createdAt, String decidedBy, Instant decidedAt, String decisionRationale, String consumedBy,
            Instant consumedAt, String correlationId) {
        static ApprovalResponse of(ApprovalRequest r) {
            return new ApprovalResponse(r.id(), r.payload().action(), r.payload().targetType(), r.payload().targetId(),
                    r.payload().environment(), r.payload().arguments(), r.payloadHash(), r.justification(), r.requestedBy(),
                    r.requesterType(), r.riskLevel().name(), r.evidenceRefs(), r.traceIds(), r.status().name(),
                    r.policyVersion(), r.expiresAt(), r.createdAt(), r.decidedBy(), r.decidedAt(), r.decisionRationale(),
                    r.consumedBy(), r.consumedAt(), r.correlationId());
        }
    }

    public record ApprovalDetail(ApprovalResponse approval, List<ApprovalRepository.DecisionRecord> history) {
    }

    public record AuditEventRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@:-]{1,128}") String actor,
            @NotBlank @Pattern(regexp = "USER|SERVICE|AGENT") String actorType,
            @NotBlank @Pattern(regexp = "[A-Z0-9_]{3,64}") String action,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,64}") String resourceType,
            @NotBlank @Size(max = 128) String resourceId,
            @Pattern(regexp = "[A-Za-z0-9._-]{1,128}") String correlationId,
            @NotBlank @Pattern(regexp = "[A-Z_]{2,32}") String outcome,
            @Size(max = 32) Map<@Pattern(regexp = "[A-Za-z0-9_.-]{1,64}") String, @Size(max = 512) String> details) {
    }

    public record AuditEventResponse(
            long sequence, UUID id, Instant occurredAt, String actor, String actorType, String recordedBy, String action,
            String resourceType, String resourceId, String correlationId, String outcome, Map<String, String> details,
            String previousHash, String hash) {
        static AuditEventResponse of(AuditEvent e) {
            return new AuditEventResponse(e.sequence(), e.id(), e.occurredAt(), e.actor(), e.actorType(), e.recordedBy(),
                    e.action(), e.resourceType(), e.resourceId(), e.correlationId(), e.outcome(), e.details(),
                    e.previousHash(), e.hash());
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
