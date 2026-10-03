package com.fixai.platform.certification.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single-use consumption of a human approval in workflow-service. The caller presents the action it is about to perform;
 * workflow-service re-hashes it and compares it with the approved payload, so an approval for one plan cannot authorise
 * another.
 */
public interface ApprovalPort {

    String START_EXTERNAL_CERTIFICATION = "START_EXTERNAL_CERTIFICATION";

    ConsumeOutcome consume(UUID approvalId, ApprovalPayload payload, String correlationId);

    record ApprovalPayload(String action, String targetType, String targetId, String environment, Map<String, Object> arguments) {
    }

    record ConsumeOutcome(boolean consumed, String payloadHash, List<String> codes, String detail) {
        public static ConsumeOutcome refused(List<String> codes, String detail) {
            return new ConsumeOutcome(false, null, List.copyOf(codes), detail);
        }
    }
}
