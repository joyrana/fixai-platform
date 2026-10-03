package com.fixai.platform.workflow.domain.approval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned approval policy. Pure and side-effect free; every decision references {@link #VERSION}.
 *
 * <ul>
 *   <li>Only allow-listed actions can be requested.</li>
 *   <li>PRODUCTION actions are refused (production-affecting actions are blocked by default).</li>
 *   <li>Four-eyes: a requester can never decide their own request.</li>
 *   <li>Only REVIEWER or ADMIN may decide; only SERVICE or ADMIN may consume; consumption is single-use and requires
 *       the payload hash to match.</li>
 *   <li>Expired requests can never be approved or consumed.</li>
 * </ul>
 */
public final class ApprovalPolicy {

    public static final String VERSION = "approval-policy/2026-10-03.1";
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    public static final Duration MAX_TTL = Duration.ofDays(7);

    /** Allow-listed actions and their minimum risk. */
    public static final Map<String, RiskLevel> ACTIONS = Map.of(
            "ACTIVATE_SESSION_CONFIG", RiskLevel.MEDIUM,
            "START_EXTERNAL_CERTIFICATION", RiskLevel.MEDIUM,
            "APPLY_REMEDIATION", RiskLevel.HIGH,
            "AI_PRIVILEGED_TOOL_CALL", RiskLevel.HIGH);

    public static final Set<String> ENVIRONMENTS = Set.of("SIMULATOR", "TEST", "UAT", "PRODUCTION");

    public record Rejection(String code, String message) {
    }

    public List<Rejection> validateCreation(ApprovalPayload payload, String justification, Duration ttl) {
        List<Rejection> problems = new ArrayList<>();
        if (!ACTIONS.containsKey(payload.action())) {
            problems.add(new Rejection("ACTION_NOT_ALLOWED", "Action " + payload.action() + " is not approvable"));
        }
        if (!ENVIRONMENTS.contains(payload.environment())) {
            problems.add(new Rejection("UNKNOWN_ENVIRONMENT", "Unknown environment " + payload.environment()));
        } else if ("PRODUCTION".equals(payload.environment())) {
            problems.add(new Rejection("PRODUCTION_BLOCKED", "Production-affecting actions are blocked by policy " + VERSION));
        }
        if (justification == null || justification.strip().length() < 10) {
            problems.add(new Rejection("JUSTIFICATION_REQUIRED", "A business justification of at least 10 characters is required"));
        }
        if (ttl != null && (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0)) {
            problems.add(new Rejection("INVALID_TTL", "Expiry must be within " + MAX_TTL.toDays() + " days"));
        }
        return problems;
    }

    public RiskLevel riskFor(ApprovalPayload payload, RiskLevel requested) {
        RiskLevel derived = ACTIONS.getOrDefault(payload.action(), RiskLevel.HIGH);
        if ("UAT".equals(payload.environment())) {
            derived = derived.max(RiskLevel.MEDIUM);
        }
        return derived.max(requested);
    }

    public Optional<Rejection> validateDecision(ApprovalRequest request, String reviewer, Set<String> reviewerRoles,
                                                String rationale, Instant now) {
        if (request.status() == ApprovalStatus.EXPIRED || request.isExpiredAt(now)) {
            return Optional.of(new Rejection("EXPIRED", "Approval request has expired"));
        }
        if (request.status() != ApprovalStatus.PENDING) {
            return Optional.of(new Rejection("NOT_PENDING", "Approval request is " + request.status()));
        }
        if (!reviewerRoles.contains("REVIEWER") && !reviewerRoles.contains("ADMIN")) {
            return Optional.of(new Rejection("NOT_A_REVIEWER", "Only reviewers may decide approval requests"));
        }
        if (reviewer.equals(request.requestedBy())) {
            return Optional.of(new Rejection("FOUR_EYES", "Requesters cannot decide their own approval requests"));
        }
        if (rationale == null || rationale.strip().length() < 10) {
            return Optional.of(new Rejection("RATIONALE_REQUIRED", "A decision rationale of at least 10 characters is required"));
        }
        return Optional.empty();
    }

    public Optional<Rejection> validateConsumption(ApprovalRequest request, ApprovalPayload presented, Set<String> consumerRoles, Instant now) {
        if (!consumerRoles.contains("SERVICE") && !consumerRoles.contains("ADMIN")) {
            return Optional.of(new Rejection("NOT_AN_EXECUTOR", "Only platform services may consume approvals"));
        }
        if (request.status() == ApprovalStatus.CONSUMED) {
            return Optional.of(new Rejection("ALREADY_CONSUMED", "Approval was already used"));
        }
        if (request.isExpiredAt(now) || request.status() == ApprovalStatus.EXPIRED) {
            return Optional.of(new Rejection("EXPIRED", "Approval has expired"));
        }
        if (request.status() != ApprovalStatus.APPROVED) {
            return Optional.of(new Rejection("NOT_APPROVED", "Approval request is " + request.status()));
        }
        if (!request.payloadHash().equals(presented.hash())) {
            return Optional.of(new Rejection("PAYLOAD_MISMATCH",
                    "The action presented for execution differs from the approved action"));
        }
        return Optional.empty();
    }

    public Optional<Rejection> validateCancellation(ApprovalRequest request, String actor, Set<String> roles) {
        if (request.status() != ApprovalStatus.PENDING && request.status() != ApprovalStatus.APPROVED) {
            return Optional.of(new Rejection("NOT_CANCELLABLE", "Approval request is " + request.status()));
        }
        if (!actor.equals(request.requestedBy()) && !roles.contains("ADMIN")) {
            return Optional.of(new Rejection("NOT_REQUESTER", "Only the requester or an administrator may cancel"));
        }
        return Optional.empty();
    }
}
