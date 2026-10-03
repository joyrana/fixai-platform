package com.fixai.platform.workflow.application.port;

import com.fixai.platform.workflow.domain.audit.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Append-only audit store. Implementations must serialise appends so the hash chain is linear. */
public interface AuditRepository {

    AuditEvent append(UUID id, Instant occurredAt, String actor, String actorType, String recordedBy, String action,
                      String resourceType, String resourceId, String correlationId, String outcome, Map<String, String> details);

    List<AuditEvent> list(String resourceType, String resourceId, String action, int page, int size);

    long count(String resourceType, String resourceId, String action);

    /** Streams every event in sequence order. */
    void forEachInOrder(Consumer<AuditEvent> consumer);
}
