package com.fixai.platform.workflow.application.service;

import com.fixai.platform.web.Actor;
import com.fixai.platform.workflow.application.port.ApprovalRepository;
import com.fixai.platform.workflow.domain.approval.ApprovalPayload;
import com.fixai.platform.workflow.domain.approval.ApprovalPolicy;
import com.fixai.platform.workflow.domain.approval.ApprovalRequest;
import com.fixai.platform.workflow.domain.approval.ApprovalStatus;
import com.fixai.platform.workflow.domain.approval.Decision;
import com.fixai.platform.workflow.domain.approval.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable human-approval workflow. Approval is enforced server-side: an action can execute only by consuming an
 * APPROVED, unexpired request whose payload hash matches the action presented at execution time.
 */
public class ApprovalService {

    private static final Set<String> GLOBAL_READERS = Set.of("REVIEWER", "ADMIN", "AUDITOR", "SERVICE");

    private final ApprovalRepository approvals;
    private final AuditService audit;
    private final ApprovalPolicy policy;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, AuditService audit, ApprovalPolicy policy, Clock clock) {
        this.approvals = approvals;
        this.audit = audit;
        this.policy = policy;
        this.clock = clock;
    }

    public record CreateCommand(
            ApprovalPayload payload, String justification, RiskLevel requestedRisk, List<String> evidenceRefs,
            List<String> traceIds, Duration ttl, String onBehalfOf) {
    }

    public record CreateResult(ApprovalRequest request, boolean created) {
    }

    public CreateResult create(CreateCommand command, Actor actor, String correlationId, String idempotencyKey) {
        String requester = requester(command, actor);
        if (idempotencyKey != null) {
            Optional<ApprovalRequest> existing = approvals.findByIdempotencyKey(requester, idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().payloadHash().equals(command.payload().hash())) {
                    throw new WorkflowExceptions.Conflict("Idempotency-Key already used for a different approval payload");
                }
                return new CreateResult(existing.get(), false);
            }
        }
        List<ApprovalPolicy.Rejection> problems = policy.validateCreation(command.payload(), command.justification(), command.ttl());
        if (!problems.isEmpty()) {
            audit.append(requester, actor.type().name(), actor.id(), "APPROVAL_REQUEST_REFUSED", "approval", "-",
                    correlationId, "REFUSED", Map.of("codes", String.join(",", codes(problems)),
                            "action", String.valueOf(command.payload().action())));
            throw new WorkflowExceptions.PolicyViolation(codes(problems),
                    String.join("; ", problems.stream().map(ApprovalPolicy.Rejection::message).toList()));
        }
        Instant now = clock.instant();
        Duration ttl = command.ttl() == null ? ApprovalPolicy.DEFAULT_TTL : command.ttl();
        ApprovalRequest request = new ApprovalRequest(UUID.randomUUID(), command.payload(), command.payload().hash(),
                command.justification().strip(), requester, actor.type().name(),
                policy.riskFor(command.payload(), command.requestedRisk()), command.evidenceRefs(), command.traceIds(),
                ApprovalStatus.PENDING, ApprovalPolicy.VERSION, now.plus(ttl), now, null, null, null, null, null,
                correlationId, requester.equals(actor.id()) ? null : actor.id());
        approvals.insert(request, idempotencyKey);
        audit.append(requester, actor.type().name(), actor.id(), "APPROVAL_REQUESTED", "approval", request.id().toString(),
                correlationId, "PENDING", details(request));
        return new CreateResult(request, true);
    }

    public ApprovalRequest get(UUID id, Actor actor) {
        ApprovalRequest request = refreshExpiry(load(id));
        if (!canRead(request, actor)) {
            throw new WorkflowExceptions.NotFound("Approval request", id);
        }
        return request;
    }

    public List<ApprovalRequest> list(ApprovalStatus status, Actor actor, int page, int size) {
        expireDue();
        return approvals.list(status, readsAll(actor) ? null : actor.id(), page, size);
    }

    public long count(ApprovalStatus status, Actor actor) {
        return approvals.count(status, readsAll(actor) ? null : actor.id());
    }

    public List<ApprovalRepository.DecisionRecord> history(UUID id, Actor actor) {
        get(id, actor);
        return approvals.decisions(id);
    }

    public ApprovalRequest decide(UUID id, Decision decision, String rationale, Actor actor, String correlationId) {
        ApprovalRequest request = refreshExpiry(load(id));
        Instant now = clock.instant();
        Optional<ApprovalPolicy.Rejection> rejection = policy.validateDecision(request, actor.id(), actor.roles(), rationale, now);
        if (rejection.isPresent()) {
            refuse(request, actor, correlationId, "APPROVAL_DECISION_REFUSED", rejection.get());
        }
        ApprovalStatus target = switch (decision) {
            case APPROVE -> ApprovalStatus.APPROVED;
            case REJECT -> ApprovalStatus.REJECTED;
            case REQUEST_CHANGES -> ApprovalStatus.CHANGES_REQUESTED;
        };
        if (!approvals.transition(id, Set.of(ApprovalStatus.PENDING), target, actor.id(), now, rationale.strip())) {
            throw new WorkflowExceptions.Conflict("Approval request was decided concurrently");
        }
        approvals.recordDecision(id, decision.name(), actor.id(), rationale.strip(), ApprovalPolicy.VERSION, now);
        audit.append(actor.id(), actor.type().name(), actor.id(), "APPROVAL_" + decision.name(), "approval", id.toString(),
                correlationId, target.name(), Map.of("policyVersion", ApprovalPolicy.VERSION, "payloadHash", request.payloadHash()));
        return load(id);
    }

    public ApprovalRequest cancel(UUID id, String reason, Actor actor, String correlationId) {
        ApprovalRequest request = refreshExpiry(load(id));
        Optional<ApprovalPolicy.Rejection> rejection = policy.validateCancellation(request, actor.id(), actor.roles());
        if (rejection.isPresent()) {
            refuse(request, actor, correlationId, "APPROVAL_CANCEL_REFUSED", rejection.get());
        }
        Instant now = clock.instant();
        String rationale = reason == null || reason.isBlank() ? "Cancelled" : reason.strip();
        if (!approvals.transition(id, Set.of(ApprovalStatus.PENDING, ApprovalStatus.APPROVED), ApprovalStatus.CANCELLED,
                actor.id(), now, rationale)) {
            throw new WorkflowExceptions.Conflict("Approval request changed concurrently");
        }
        approvals.recordDecision(id, "CANCEL", actor.id(), rationale, ApprovalPolicy.VERSION, now);
        audit.append(actor.id(), actor.type().name(), actor.id(), "APPROVAL_CANCELLED", "approval", id.toString(),
                correlationId, "CANCELLED", Map.of());
        return load(id);
    }

    /**
     * Single-use consumption at execution time. The executor presents the action it is about to perform; it is
     * re-hashed and must equal the approved hash.
     */
    public ApprovalRequest consume(UUID id, ApprovalPayload presented, Actor actor, String correlationId) {
        ApprovalRequest request = refreshExpiry(load(id));
        Instant now = clock.instant();
        Optional<ApprovalPolicy.Rejection> rejection = policy.validateConsumption(request, presented, actor.roles(), now);
        if (rejection.isPresent()) {
            refuse(request, actor, correlationId, "APPROVAL_CONSUME_REFUSED", rejection.get());
        }
        if (!approvals.consume(id, actor.id(), now)) {
            throw new WorkflowExceptions.PolicyViolation(List.of("ALREADY_CONSUMED"), "Approval was already used");
        }
        approvals.recordDecision(id, "CONSUME", actor.id(), "Executed " + presented.action(), ApprovalPolicy.VERSION, now);
        audit.append(actor.id(), actor.type().name(), actor.id(), "APPROVAL_CONSUMED", "approval", id.toString(),
                correlationId, "CONSUMED", Map.of("payloadHash", request.payloadHash(), "action", presented.action()));
        return load(id);
    }

    /** Marks overdue requests EXPIRED; safe to call repeatedly. */
    public int expireDue() {
        List<UUID> expired = approvals.expireDue(clock.instant());
        for (UUID id : expired) {
            audit.append("workflow-service", "SERVICE", "workflow-service", "APPROVAL_EXPIRED", "approval", id.toString(),
                    null, "EXPIRED", Map.of());
        }
        return expired.size();
    }

    private ApprovalRequest refreshExpiry(ApprovalRequest request) {
        if (request.isExpiredAt(clock.instant())) {
            expireDue();
            return load(request.id());
        }
        return request;
    }

    private void refuse(ApprovalRequest request, Actor actor, String correlationId, String action, ApprovalPolicy.Rejection rejection) {
        audit.append(actor.id(), actor.type().name(), actor.id(), action, "approval", request.id().toString(), correlationId,
                "REFUSED", Map.of("code", rejection.code()));
        throw new WorkflowExceptions.PolicyViolation(List.of(rejection.code()), rejection.message());
    }

    private ApprovalRequest load(UUID id) {
        return approvals.find(id).orElseThrow(() -> new WorkflowExceptions.NotFound("Approval request", id));
    }

    /**
     * Services and agents may file requests on behalf of the person who triggered them, so that person is the requester
     * for four-eyes purposes (they cannot approve their own AI-assisted request). The filing identity is recorded in the
     * audit log as recordedBy and as requesterType. Users always file as themselves.
     */
    private static String requester(CreateCommand command, Actor actor) {
        if (actor.type() != Actor.Type.USER && command.onBehalfOf() != null && !command.onBehalfOf().isBlank()) {
            return command.onBehalfOf();
        }
        return actor.id();
    }

    private static boolean readsAll(Actor actor) {
        return actor.roles().stream().anyMatch(GLOBAL_READERS::contains);
    }

    private static boolean canRead(ApprovalRequest request, Actor actor) {
        return readsAll(actor) || request.isOwnedBy(actor.id());
    }

    private static List<String> codes(List<ApprovalPolicy.Rejection> problems) {
        return problems.stream().map(ApprovalPolicy.Rejection::code).toList();
    }

    private static Map<String, String> details(ApprovalRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("action", request.payload().action());
        details.put("targetType", request.payload().targetType());
        details.put("targetId", request.payload().targetId());
        details.put("environment", request.payload().environment());
        details.put("risk", request.riskLevel().name());
        details.put("payloadHash", request.payloadHash());
        details.put("expiresAt", request.expiresAt().toString());
        return details;
    }
}
