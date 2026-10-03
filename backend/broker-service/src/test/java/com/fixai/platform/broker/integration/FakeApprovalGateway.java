package com.fixai.platform.broker.integration;

import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.domain.session.SessionConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for workflow-service with the same binding semantics: exact arguments, single use. */
class FakeApprovalGateway implements ApprovalGateway {

    record Entry(Map<String, Object> arguments, String status) {
    }

    final Map<UUID, Entry> approvals = new ConcurrentHashMap<>();

    @Override
    public ApprovalTicket requestActivation(SessionConfig config, String justification, String requestedBy) {
        UUID id = UUID.randomUUID();
        approvals.put(id, new Entry(config.approvalArguments(), "PENDING"));
        return new ApprovalTicket(id, hash(config.approvalArguments()), "PENDING");
    }

    void decide(UUID id, String status) {
        approvals.computeIfPresent(id, (k, e) -> new Entry(e.arguments(), status));
    }

    @Override
    public ConsumeResult consume(UUID approvalId, SessionConfig config) {
        Entry entry = approvals.get(approvalId);
        if (entry == null || !"APPROVED".equals(entry.status())) {
            return new ConsumeResult(false, null, List.of(entry != null && "CONSUMED".equals(entry.status())
                    ? "ALREADY_CONSUMED" : "NOT_APPROVED"), "Not approved");
        }
        if (!entry.arguments().equals(config.approvalArguments())) {
            return new ConsumeResult(false, null, List.of("PAYLOAD_MISMATCH"), "Mismatch");
        }
        approvals.put(approvalId, new Entry(entry.arguments(), "CONSUMED"));
        return new ConsumeResult(true, hash(entry.arguments()), List.of(), null);
    }

    @Override
    public Optional<String> status(UUID approvalId) {
        return Optional.ofNullable(approvals.get(approvalId)).map(Entry::status);
    }

    private static String hash(Map<String, Object> arguments) {
        return String.format("%064x", arguments.toString().hashCode() & 0xffffffffL);
    }
}
