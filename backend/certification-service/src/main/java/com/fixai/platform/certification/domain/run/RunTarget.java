package com.fixai.platform.certification.domain.run;

import java.util.UUID;

/**
 * Resolved connection target of a run. Simulator targets are synthetic; session-config targets must be approved and
 * in a non-production environment before a run is accepted.
 */
public record RunTarget(
        Type type, String host, int port, String targetCompId, String simulatorProfile, UUID sessionConfigId,
        String environment) {

    public enum Type {
        SIMULATOR,
        SESSION_CONFIG
    }

    /** External targets use fixed CompIDs, so their scenarios must run one at a time. */
    public boolean allowsParallelScenarios() {
        return type == Type.SIMULATOR;
    }
}
