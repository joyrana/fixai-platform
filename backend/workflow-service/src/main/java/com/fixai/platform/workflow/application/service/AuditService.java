package com.fixai.platform.workflow.application.service;

import com.fixai.platform.workflow.application.port.AuditRepository;
import com.fixai.platform.workflow.domain.audit.AuditEvent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Appends and verifies the hash-chained audit log. */
public class AuditService {

    private final AuditRepository repository;
    private final Clock clock;

    public AuditService(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AuditEvent append(String actor, String actorType, String recordedBy, String action, String resourceType,
                             String resourceId, String correlationId, String outcome, Map<String, String> details) {
        return repository.append(UUID.randomUUID(), clock.instant(), actor, actorType, recordedBy, action, resourceType,
                resourceId, correlationId, outcome, details);
    }

    public List<AuditEvent> list(String resourceType, String resourceId, String action, int page, int size) {
        return repository.list(resourceType, resourceId, action, page, size);
    }

    public long count(String resourceType, String resourceId, String action) {
        return repository.count(resourceType, resourceId, action);
    }

    public record Verification(boolean valid, long eventsChecked, Long firstInvalidSequence, String headHash) {
    }

    /** Recomputes every hash in sequence order. Any edit, deletion or reordering is detected. */
    public Verification verify() {
        AtomicReference<String> previous = new AtomicReference<>(AuditEvent.GENESIS);
        AtomicLong checked = new AtomicLong();
        AtomicLong expectedSequence = new AtomicLong(1);
        AtomicReference<Long> firstInvalid = new AtomicReference<>();
        repository.forEachInOrder(event -> {
            if (firstInvalid.get() != null) {
                return;
            }
            checked.incrementAndGet();
            if (event.sequence() != expectedSequence.getAndIncrement() || !event.verifies(previous.get())) {
                firstInvalid.set(event.sequence());
                return;
            }
            previous.set(event.hash());
        });
        return new Verification(firstInvalid.get() == null, checked.get(), firstInvalid.get(), previous.get());
    }
}
