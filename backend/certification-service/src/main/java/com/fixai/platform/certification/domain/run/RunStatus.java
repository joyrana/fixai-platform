package com.fixai.platform.certification.domain.run;

public enum RunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    ERROR;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == ERROR;
    }
}
