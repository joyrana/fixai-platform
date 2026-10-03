package com.fixai.platform.workflow.domain.approval;

public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED,
    CANCELLED,
    EXPIRED,
    CONSUMED;

    public boolean isTerminal() {
        return this != PENDING && this != APPROVED;
    }
}
