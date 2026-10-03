package com.fixai.platform.workflow.domain.approval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Approval request aggregate. Every field except status/decision/consumption is immutable after creation. */
public record ApprovalRequest(
        UUID id,
        ApprovalPayload payload,
        String payloadHash,
        String justification,
        String requestedBy,
        String requesterType,
        RiskLevel riskLevel,
        List<String> evidenceRefs,
        List<String> traceIds,
        ApprovalStatus status,
        String policyVersion,
        Instant expiresAt,
        Instant createdAt,
        String decidedBy,
        Instant decidedAt,
        String decisionRationale,
        String consumedBy,
        Instant consumedAt,
        String correlationId,
        String filedBy) {

    public ApprovalRequest {
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        traceIds = traceIds == null ? List.of() : List.copyOf(traceIds);
    }

    /** True for the requester and for the service or agent identity that filed the request on their behalf. */
    public boolean isOwnedBy(String actorId) {
        return actorId.equals(requestedBy) || actorId.equals(filedBy);
    }

    public boolean isExpiredAt(Instant now) {
        return (status == ApprovalStatus.PENDING || status == ApprovalStatus.APPROVED) && !now.isBefore(expiresAt);
    }
}
