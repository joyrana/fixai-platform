package com.fixai.platform.broker.application.port.outbound;

import java.util.Map;

/** Audit sink; implementations resolve the acting identity and correlation ID from the request context. */
public interface AuditPort {

    void record(String action, String resourceType, String resourceId, String outcome, Map<String, String> details);
}
