package com.fixai.platform.broker.domain.session;

import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixVersion;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FIX session configuration owned by broker-service.
 *
 * @param credentialRef reference to a secret in the secret manager (e.g. {@code vault:fix/brk/uat}); raw credentials are
 *        never stored or returned
 * @param version optimistic-lock version
 */
public record SessionConfig(
        UUID id,
        UUID brokerId,
        String name,
        SessionEnvironment environment,
        FixVersion fixVersion,
        FixSessionSpec.Role role,
        String senderCompId,
        String targetCompId,
        String host,
        int port,
        int heartbeatIntervalSeconds,
        int reconnectIntervalSeconds,
        boolean resetOnLogon,
        boolean resetOnLogout,
        boolean resetOnDisconnect,
        String credentialRef,
        SessionConfigStatus status,
        UUID approvalRequestId,
        String approvedPayloadHash,
        String activatedBy,
        Instant activatedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public FixSessionSpec toSpec() {
        return new FixSessionSpec(role, fixVersion, senderCompId, targetCompId, host, port, heartbeatIntervalSeconds,
                reconnectIntervalSeconds, 10, resetOnLogon, resetOnLogout, resetOnDisconnect, true,
                FixSessionSpec.StoreType.MEMORY, null, null);
    }

    /**
     * The exact values a reviewer approves. Any change produces a different approval payload hash, so an approval can
     * never be applied to a configuration other than the one reviewed.
     */
    public Map<String, Object> approvalArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("brokerId", brokerId.toString());
        arguments.put("name", name);
        arguments.put("fixVersion", fixVersion.name());
        arguments.put("role", role.name());
        arguments.put("senderCompId", senderCompId);
        arguments.put("targetCompId", targetCompId);
        arguments.put("host", host);
        arguments.put("port", port);
        arguments.put("heartbeatIntervalSeconds", heartbeatIntervalSeconds);
        arguments.put("reconnectIntervalSeconds", reconnectIntervalSeconds);
        arguments.put("resetOnLogon", resetOnLogon);
        arguments.put("resetOnLogout", resetOnLogout);
        arguments.put("resetOnDisconnect", resetOnDisconnect);
        arguments.put("credentialRef", credentialRef == null ? "" : credentialRef);
        arguments.put("configVersion", version);
        return arguments;
    }
}
