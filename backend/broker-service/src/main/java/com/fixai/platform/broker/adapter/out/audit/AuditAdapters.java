package com.fixai.platform.broker.adapter.out.audit;

import com.fixai.platform.broker.application.port.outbound.AuditPort;
import com.fixai.platform.web.Actor;
import com.fixai.platform.web.AuditOutbox;
import com.fixai.platform.web.CorrelationIdFilter;
import com.fixai.platform.web.CurrentActor;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Audit adapters: transactional outbox when workflow-service is configured, structured log otherwise. */
public final class AuditAdapters {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private AuditAdapters() {
    }

    public static AuditPort outbox(AuditOutbox outbox, CurrentActor actors) {
        return (action, resourceType, resourceId, outcome, details) -> {
            Actor actor = actor(actors);
            outbox.record(actor.id(), actor.type(), action, resourceType, resourceId, CorrelationIdFilter.currentOrNew(),
                    outcome, details);
        };
    }

    public static AuditPort log(CurrentActor actors) {
        return (action, resourceType, resourceId, outcome, details) -> AUDIT.info(
                "action={} actor={} resource={}/{} outcome={} correlationId={} details={}", action, actor(actors).id(),
                resourceType, resourceId, outcome, CorrelationIdFilter.currentOrNew(), details);
    }

    private static Actor actor(CurrentActor actors) {
        try {
            return actors.get();
        } catch (IllegalStateException exception) {
            return new Actor("broker-service", Actor.Type.SERVICE, java.util.Set.of("SERVICE"));
        }
    }

    /** Detail maps are bounded so audit records stay small and free of payload content. */
    static Map<String, String> bounded(Map<String, String> details) {
        return details.size() > 32 ? Map.of("truncated", "true") : details;
    }
}
