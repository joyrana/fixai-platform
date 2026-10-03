package com.fixai.platform.broker.domain.session;

/**
 * DRAFT -> PENDING_APPROVAL -> APPROVED. Editing any configuration returns it to DRAFT and invalidates prior approval.
 * REJECTED/CHANGES_REQUESTED come from the reviewer's decision; RETIRED is terminal.
 */
public enum SessionConfigStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED,
    RETIRED
}
