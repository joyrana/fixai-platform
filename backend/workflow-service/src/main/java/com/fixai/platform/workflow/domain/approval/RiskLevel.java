package com.fixai.platform.workflow.domain.approval;

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public RiskLevel max(RiskLevel other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }
}
