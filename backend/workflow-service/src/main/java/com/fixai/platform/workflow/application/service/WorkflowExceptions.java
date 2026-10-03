package com.fixai.platform.workflow.application.service;

import java.util.List;

public final class WorkflowExceptions {

    private WorkflowExceptions() {
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String what, Object id) {
            super(what + " not found: " + id);
        }
    }

    /** A policy rule refused the operation; {@code code} is stable for clients and evaluation datasets. */
    public static final class PolicyViolation extends RuntimeException {
        private final List<String> codes;

        public PolicyViolation(List<String> codes, String message) {
            super(message);
            this.codes = List.copyOf(codes);
        }

        public List<String> codes() {
            return codes;
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
