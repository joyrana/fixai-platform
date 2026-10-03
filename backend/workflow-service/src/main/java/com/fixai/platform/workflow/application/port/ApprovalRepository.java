package com.fixai.platform.workflow.application.port;

import com.fixai.platform.workflow.domain.approval.ApprovalRequest;
import com.fixai.platform.workflow.domain.approval.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port for approvals. Status changes are compare-and-set so concurrent decisions cannot both succeed. */
public interface ApprovalRepository {

    void insert(ApprovalRequest request, String idempotencyKey);

    Optional<ApprovalRequest> find(UUID id);

    Optional<ApprovalRequest> findByIdempotencyKey(String requestedBy, String idempotencyKey);

    List<ApprovalRequest> list(ApprovalStatus status, String requestedBy, int page, int size);

    long count(ApprovalStatus status, String requestedBy);

    /** Atomically moves {@code id} from one of {@code from} to {@code to}; returns false if the status changed meanwhile. */
    boolean transition(UUID id, Set<ApprovalStatus> from, ApprovalStatus to, String actor, Instant at, String rationale);

    /** Atomically marks an APPROVED, unexpired request CONSUMED. */
    boolean consume(UUID id, String consumer, Instant at);

    /** Marks PENDING/APPROVED requests past expiry as EXPIRED; returns their IDs. */
    List<UUID> expireDue(Instant now);

    void recordDecision(UUID approvalId, String decision, String actor, String rationale, String policyVersion, Instant at);

    List<DecisionRecord> decisions(UUID approvalId);

    record DecisionRecord(String decision, String actor, String rationale, String policyVersion, Instant at) {
    }
}
