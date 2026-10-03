package com.fixai.platform.workflow.domain.audit;

import com.fixai.platform.workflow.domain.CanonicalJson;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Append-only audit event (schemaVersion 1). {@code hash = SHA-256(previousHash + "\n" + canonical(fields))} links
 * every event to its predecessor so any modification or deletion breaks verification.
 *
 * @param actor who performed the action (user, service or agent identity)
 * @param recordedBy the authenticated identity that submitted the event (a service may record on behalf of a user)
 */
public record AuditEvent(
        long sequence,
        UUID id,
        Instant occurredAt,
        String actor,
        String actorType,
        String recordedBy,
        String action,
        String resourceType,
        String resourceId,
        String correlationId,
        String outcome,
        Map<String, String> details,
        String previousHash,
        String hash) {

    public static final String GENESIS = "0".repeat(64);

    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static String computeHash(long sequence, UUID id, Instant occurredAt, String actor, String actorType,
                                     String recordedBy, String action, String resourceType, String resourceId,
                                     String correlationId, String outcome, Map<String, String> details, String previousHash) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("sequence", sequence);
        document.put("id", id.toString());
        document.put("occurredAt", occurredAt.toString());
        document.put("actor", actor);
        document.put("actorType", actorType);
        document.put("recordedBy", recordedBy);
        document.put("action", action);
        document.put("resourceType", resourceType);
        document.put("resourceId", resourceId);
        document.put("correlationId", correlationId);
        document.put("outcome", outcome);
        document.put("details", new TreeMap<>(details == null ? Map.of() : details));
        return CanonicalJson.sha256(previousHash + "\n" + CanonicalJson.write(document));
    }

    public boolean verifies(String expectedPrevious) {
        return previousHash.equals(expectedPrevious) && hash.equals(computeHash(sequence, id, occurredAt, actor, actorType,
                recordedBy, action, resourceType, resourceId, correlationId, outcome, details, previousHash));
    }
}
