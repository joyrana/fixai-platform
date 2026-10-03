package com.fixai.platform.broker.application.service;

import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.application.port.outbound.AuditPort;
import com.fixai.platform.broker.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.broker.application.port.outbound.SessionConfigRepository;
import com.fixai.platform.broker.application.port.outbound.UnitOfWork;
import com.fixai.platform.broker.domain.broker.Broker;
import com.fixai.platform.broker.domain.broker.BrokerStatus;
import com.fixai.platform.broker.domain.session.SessionConfig;
import com.fixai.platform.broker.domain.session.SessionConfigPolicy;
import com.fixai.platform.broker.domain.session.SessionConfigStatus;
import com.fixai.platform.broker.domain.session.SessionEnvironment;
import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixSessionSpecValidator;
import com.fixai.platform.fixcore.FixVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle of FIX session configurations: draft, validate, submit for approval, activate (consuming the approval
 * bound to the exact configuration), retire.
 */
public class SessionConfigService {

    private static final Set<SessionConfigStatus> SUBMITTABLE =
            Set.of(SessionConfigStatus.DRAFT, SessionConfigStatus.REJECTED, SessionConfigStatus.CHANGES_REQUESTED);

    private final SessionConfigRepository configs;
    private final BrokerRepositoryPort brokers;
    private final ApprovalGateway approvals;
    private final AuditPort audit;
    private final SessionConfigPolicy policy;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public SessionConfigService(SessionConfigRepository configs, BrokerRepositoryPort brokers, ApprovalGateway approvals,
                                AuditPort audit, SessionConfigPolicy policy, UnitOfWork unitOfWork, Clock clock) {
        this.configs = configs;
        this.brokers = brokers;
        this.approvals = approvals;
        this.audit = audit;
        this.policy = policy;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public record ConfigFields(
            String name, SessionEnvironment environment, FixVersion fixVersion, FixSessionSpec.Role role,
            String senderCompId, String targetCompId, String host, int port, int heartbeatIntervalSeconds,
            int reconnectIntervalSeconds, boolean resetOnLogon, boolean resetOnLogout, boolean resetOnDisconnect,
            String credentialRef) {
    }

    public SessionConfig create(UUID brokerId, ConfigFields fields, String actor) {
        Broker broker = activeBroker(brokerId);
        Instant now = clock.instant();
        SessionConfig config = build(UUID.randomUUID(), broker.id(), fields, SessionConfigStatus.DRAFT, actor, now, now, 1);
        requireValid(config);
        if (configs.duplicateExists(config)) {
            throw new SessionConfigExceptions.Conflict("A configuration with the same environment, FIX version and CompIDs exists",
                    List.of("DUPLICATE_SESSION"));
        }
        unitOfWork.run(() -> {
            configs.insert(config);
            audit.record("SESSION_CONFIG_CREATED", "session-config", config.id().toString(), "DRAFT",
                    Map.of("brokerId", brokerId.toString(), "environment", config.environment().name(),
                            "fixVersion", config.fixVersion().name()));
        });
        return config;
    }

    public SessionConfig update(UUID id, ConfigFields fields, long expectedVersion, String actor) {
        SessionConfig existing = get(id);
        if (existing.status() == SessionConfigStatus.RETIRED) {
            throw new SessionConfigExceptions.Conflict("Retired configurations cannot be changed", List.of("RETIRED"));
        }
        SessionConfig updated = build(existing.id(), existing.brokerId(), fields, SessionConfigStatus.DRAFT,
                existing.createdBy(), existing.createdAt(), clock.instant(), existing.version() + 1);
        requireValid(updated);
        if (configs.duplicateExists(updated)) {
            throw new SessionConfigExceptions.Conflict("A configuration with the same environment, FIX version and CompIDs exists",
                    List.of("DUPLICATE_SESSION"));
        }
        unitOfWork.run(() -> {
            if (!configs.update(updated, expectedVersion, existing.status())) {
                throw new SessionConfigExceptions.Conflict("Configuration was modified concurrently", List.of("VERSION_CONFLICT"));
            }
            audit.record("SESSION_CONFIG_UPDATED", "session-config", id.toString(), "DRAFT",
                    Map.of("previousStatus", existing.status().name(), "version", Long.toString(updated.version())));
        });
        return updated;
    }

    public SessionConfig get(UUID id) {
        return configs.find(id).orElseThrow(() -> new SessionConfigExceptions.NotFound("Session configuration not found: " + id));
    }

    public List<SessionConfig> forBroker(UUID brokerId) {
        brokers.findById(brokerId).orElseThrow(() -> new BrokerNotFoundException("Broker not found: " + brokerId));
        return configs.findByBroker(brokerId);
    }

    public List<FixSessionSpecValidator.Violation> validate(UUID id) {
        return policy.validate(get(id));
    }

    public SessionConfig submit(UUID id, String justification, String actor) {
        SessionConfig config = get(id);
        if (!SUBMITTABLE.contains(config.status())) {
            throw new SessionConfigExceptions.Conflict("Configuration is " + config.status(), List.of("NOT_SUBMITTABLE"));
        }
        requireValid(config);
        activeBroker(config.brokerId());
        ApprovalGateway.ApprovalTicket ticket = approvals.requestActivation(config, justification, actor);
        SessionConfig pending = withApproval(config, SessionConfigStatus.PENDING_APPROVAL, ticket.approvalId(), null, null, null);
        unitOfWork.run(() -> {
            if (!configs.update(pending, config.version(), config.status())) {
                throw new SessionConfigExceptions.Conflict("Configuration was modified concurrently", List.of("VERSION_CONFLICT"));
            }
            audit.record("SESSION_CONFIG_SUBMITTED", "session-config", id.toString(), "PENDING_APPROVAL",
                    Map.of("approvalId", ticket.approvalId().toString(), "payloadHash", ticket.payloadHash()));
        });
        return get(id);
    }

    /**
     * Activates an approved configuration. Re-validates the configuration and the broker at execution time, then
     * consumes the approval for exactly this configuration; the workflow service refuses a mismatch.
     */
    public SessionConfig activate(UUID id, String actor) {
        SessionConfig config = get(id);
        if (config.status() == SessionConfigStatus.APPROVED) {
            return config;
        }
        if (config.status() != SessionConfigStatus.PENDING_APPROVAL || config.approvalRequestId() == null) {
            throw new SessionConfigExceptions.Conflict("Configuration has not been submitted for approval", List.of("NOT_SUBMITTED"));
        }
        requireValid(config);
        activeBroker(config.brokerId());
        ApprovalGateway.ConsumeResult result = approvals.consume(config.approvalRequestId(), config);
        if (!result.consumed()) {
            SessionConfigStatus next = statusAfterRefusal(config, result.codes());
            unitOfWork.run(() -> {
                if (next != config.status()) {
                    configs.update(withApproval(config, next, next == SessionConfigStatus.DRAFT ? null : config.approvalRequestId(),
                            null, null, null), config.version(), config.status());
                }
                audit.record("SESSION_CONFIG_ACTIVATION_REFUSED", "session-config", id.toString(), "REFUSED",
                        Map.of("codes", String.join(",", result.codes())));
            });
            throw new SessionConfigExceptions.Conflict(result.message() == null ? "Activation refused" : result.message(), result.codes());
        }
        SessionConfig approved = withApproval(config, SessionConfigStatus.APPROVED, config.approvalRequestId(),
                result.payloadHash(), actor, clock.instant());
        unitOfWork.run(() -> {
            if (!configs.update(approved, config.version(), config.status())) {
                throw new SessionConfigExceptions.Conflict("Configuration was modified concurrently", List.of("VERSION_CONFLICT"));
            }
            audit.record("SESSION_CONFIG_ACTIVATED", "session-config", id.toString(), "APPROVED",
                    Map.of("approvalId", config.approvalRequestId().toString(), "payloadHash", result.payloadHash()));
        });
        return get(id);
    }

    public SessionConfig retire(UUID id, String actor) {
        SessionConfig config = get(id);
        if (config.status() == SessionConfigStatus.RETIRED) {
            return config;
        }
        SessionConfig retired = withApproval(config, SessionConfigStatus.RETIRED, config.approvalRequestId(),
                config.approvedPayloadHash(), config.activatedBy(), config.activatedAt());
        unitOfWork.run(() -> {
            if (!configs.update(retired, config.version(), config.status())) {
                throw new SessionConfigExceptions.Conflict("Configuration was modified concurrently", List.of("VERSION_CONFLICT"));
            }
            audit.record("SESSION_CONFIG_RETIRED", "session-config", id.toString(), "RETIRED", Map.of());
        });
        return get(id);
    }

    private SessionConfigStatus statusAfterRefusal(SessionConfig config, List<String> codes) {
        if (codes.contains("EXPIRED") || codes.contains("PAYLOAD_MISMATCH") || codes.contains("ALREADY_CONSUMED")) {
            return SessionConfigStatus.DRAFT;
        }
        if (codes.contains("NOT_APPROVED")) {
            return approvals.status(config.approvalRequestId()).map(status -> switch (status) {
                case "REJECTED" -> SessionConfigStatus.REJECTED;
                case "CHANGES_REQUESTED" -> SessionConfigStatus.CHANGES_REQUESTED;
                case "CANCELLED", "EXPIRED" -> SessionConfigStatus.DRAFT;
                default -> config.status();
            }).orElse(config.status());
        }
        return config.status();
    }

    private Broker activeBroker(UUID brokerId) {
        Broker broker = brokers.findById(brokerId).orElseThrow(() -> new BrokerNotFoundException("Broker not found: " + brokerId));
        if (broker.status() != BrokerStatus.ACTIVE) {
            throw new SessionConfigExceptions.Conflict("Broker is " + broker.status(), List.of("BROKER_NOT_ACTIVE"));
        }
        return broker;
    }

    private void requireValid(SessionConfig config) {
        List<FixSessionSpecValidator.Violation> violations = policy.validate(config);
        if (!violations.isEmpty()) {
            throw new SessionConfigExceptions.ValidationFailed(violations);
        }
    }

    private static SessionConfig build(UUID id, UUID brokerId, ConfigFields f, SessionConfigStatus status, String createdBy,
                                       Instant createdAt, Instant updatedAt, long version) {
        return new SessionConfig(id, brokerId, f.name() == null ? null : f.name().strip(), f.environment(), f.fixVersion(),
                f.role() == null ? FixSessionSpec.Role.INITIATOR : f.role(), f.senderCompId(), f.targetCompId(), f.host(),
                f.port(), f.heartbeatIntervalSeconds(), f.reconnectIntervalSeconds(), f.resetOnLogon(), f.resetOnLogout(),
                f.resetOnDisconnect(), f.credentialRef(), status, null, null, null, null, createdBy, createdAt, updatedAt, version);
    }

    private SessionConfig withApproval(SessionConfig c, SessionConfigStatus status, UUID approvalId, String payloadHash,
                                       String activatedBy, Instant activatedAt) {
        return new SessionConfig(c.id(), c.brokerId(), c.name(), c.environment(), c.fixVersion(), c.role(), c.senderCompId(),
                c.targetCompId(), c.host(), c.port(), c.heartbeatIntervalSeconds(), c.reconnectIntervalSeconds(),
                c.resetOnLogon(), c.resetOnLogout(), c.resetOnDisconnect(), c.credentialRef(), status, approvalId,
                payloadHash, activatedBy, activatedAt, c.createdBy(), c.createdAt(), clock.instant(), c.version());
    }
}
