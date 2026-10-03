package com.fixai.platform.certification.application.port.out;

import java.util.Map;

/**
 * Outbound port for audit events. Implementations must not block the certification run on audit-store outages for
 * longer than their configured timeout, and must never include FIX message content.
 */
public interface AuditPort {

    void record(String actor, String action, String resourceType, String resourceId, String correlationId,
                String outcome, Map<String, String> details);
}
