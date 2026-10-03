package com.fixai.platform.certification.application.engine;

import com.fixai.platform.fixcore.FixVersion;

/**
 * Where and how a scenario connects. CompIDs are already resolved (for simulator targets the sender is run-scoped so
 * every scenario gets an isolated session).
 */
public record ExecutionTarget(
        FixVersion version, String host, int port, String senderCompId, String targetCompId, int defaultHeartbeatSeconds) {
}
