package com.fixai.platform.certification.application.service;

import java.util.List;
import java.util.UUID;

/** Application-level failures mapped to RFC 7807 responses by the web adapter. */
public final class CertificationExceptions {

    private CertificationExceptions() {
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String what, Object id) {
            super(what + " not found: " + id);
        }
    }

    /** Request is well-formed but semantically invalid; carries every problem found. */
    public static final class InvalidRequest extends RuntimeException {
        private final List<String> problems;

        public InvalidRequest(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** Same Idempotency-Key reused with a different request body. */
    public static final class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(UUID existingRunId) {
            super("Idempotency-Key already used for a different request (run " + existingRunId + ")");
        }
    }

    /** Target is not permitted by safety policy (e.g. production, unapproved configuration). */
    public static final class TargetNotAllowed extends RuntimeException {
        public TargetNotAllowed(String reason) {
            super(reason);
        }
    }

    /** A required human approval is missing, not approved, expired, already used or for a different payload. */
    public static final class ApprovalRefused extends RuntimeException {
        private final List<String> codes;

        public ApprovalRefused(String detail, List<String> codes) {
            super(detail);
            this.codes = List.copyOf(codes);
        }

        public List<String> codes() {
            return codes;
        }
    }

    /** Operation not valid in the run's current state. */
    public static final class InvalidState extends RuntimeException {
        public InvalidState(String message) {
            super(message);
        }
    }
}
