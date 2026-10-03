package com.fixai.platform.certification.adapter.out.audit;

import com.fixai.platform.certification.application.port.out.AuditPort;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Structured-log audit sink used when no audit service is configured (local development). */
public class LoggingAuditAdapter implements AuditPort {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    @Override
    public void record(String actor, String action, String resourceType, String resourceId, String correlationId,
                       String outcome, Map<String, String> details) {
        AUDIT.info("action={} actor={} resource={}/{} outcome={} correlationId={} details={}",
                action, actor, resourceType, resourceId, outcome, correlationId, details);
    }
}
