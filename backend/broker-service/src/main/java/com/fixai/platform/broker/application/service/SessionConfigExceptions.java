package com.fixai.platform.broker.application.service;

import com.fixai.platform.fixcore.FixSessionSpecValidator;
import java.util.List;

public final class SessionConfigExceptions {

    private SessionConfigExceptions() {
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static final class ValidationFailed extends RuntimeException {
        private final List<FixSessionSpecValidator.Violation> violations;

        public ValidationFailed(List<FixSessionSpecValidator.Violation> violations) {
            super("Session configuration is invalid");
            this.violations = List.copyOf(violations);
        }

        public List<FixSessionSpecValidator.Violation> violations() {
            return violations;
        }
    }

    /** State or concurrency conflict; {@code codes} are stable machine-readable reasons. */
    public static final class Conflict extends RuntimeException {
        private final List<String> codes;

        public Conflict(String message, List<String> codes) {
            super(message);
            this.codes = List.copyOf(codes);
        }

        public List<String> codes() {
            return codes;
        }
    }
}
