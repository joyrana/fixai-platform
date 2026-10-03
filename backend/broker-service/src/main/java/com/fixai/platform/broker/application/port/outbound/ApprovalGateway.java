package com.fixai.platform.broker.application.port.outbound;

import com.fixai.platform.broker.domain.session.SessionConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port to the workflow service, which owns approval state and policy. */
public interface ApprovalGateway {

    String ACTION = "ACTIVATE_SESSION_CONFIG";
    String TARGET_TYPE = "session-config";

    ApprovalTicket requestActivation(SessionConfig config, String justification, String requestedBy);

    /** Consumes the approval for exactly this configuration; returns refusal codes if not permitted. */
    ConsumeResult consume(UUID approvalId, SessionConfig config);

    Optional<String> status(UUID approvalId);

    record ApprovalTicket(UUID approvalId, String payloadHash, String status) {
    }

    record ConsumeResult(boolean consumed, String payloadHash, List<String> codes, String message) {
    }

    /** The workflow service could not be reached or answered unexpectedly. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
