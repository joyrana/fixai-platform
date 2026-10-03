package com.fixai.platform.certification.adapter.out.workflow;

import com.fixai.platform.certification.application.port.out.ApprovalPort;
import java.util.List;
import java.util.UUID;

/** Used when no workflow-service URL is configured: no approval can be verified, so external runs are refused. */
public class UnconfiguredApprovalAdapter implements ApprovalPort {

    @Override
    public ConsumeOutcome consume(UUID approvalId, ApprovalPayload payload, String correlationId) {
        return ConsumeOutcome.refused(List.of("APPROVALS_UNAVAILABLE"),
                "Approval service is not configured; external certification is disabled");
    }
}
