package com.fixai.platform.certification.adapter.out.audit;

import com.fixai.platform.certification.application.port.out.AuditPort;
import com.fixai.platform.web.Actor;
import com.fixai.platform.web.AuditOutbox;
import java.util.Map;

/** Writes audit events to the transactional outbox for delivery to workflow-service. */
public class OutboxAuditAdapter implements AuditPort {

    private final AuditOutbox outbox;

    public OutboxAuditAdapter(AuditOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void record(String actor, String action, String resourceType, String resourceId, String correlationId,
                       String outcome, Map<String, String> details) {
        Actor.Type type = "certification-service".equals(actor) ? Actor.Type.SERVICE : Actor.Type.USER;
        outbox.record(actor, type, action, resourceType, resourceId, correlationId, outcome, details);
    }
}
