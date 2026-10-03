package com.fixai.platform.broker.adapter.in.web.dto;

import com.fixai.platform.broker.domain.session.SessionConfig;
import com.fixai.platform.broker.domain.session.SessionEnvironment;
import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixSessionSpecValidator;
import com.fixai.platform.fixcore.FixVersion;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SessionConfigDtos {

    private SessionConfigDtos() {
    }

    /** Request body for create and update. Raw credentials are not accepted; supply a secret-store reference. */
    public record SessionConfigRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull SessionEnvironment environment,
            @NotNull FixVersion fixVersion,
            FixSessionSpec.Role role,
            @NotBlank @Size(max = 64) String senderCompId,
            @NotBlank @Size(max = 64) String targetCompId,
            @NotBlank @Size(max = 253) String host,
            @Min(1) @Max(65535) int port,
            @Min(1) @Max(300) int heartbeatIntervalSeconds,
            @Min(1) @Max(3600) int reconnectIntervalSeconds,
            boolean resetOnLogon,
            boolean resetOnLogout,
            boolean resetOnDisconnect,
            @Size(max = 256) String credentialRef,
            Long expectedVersion) {
    }

    public record SubmitRequest(@NotBlank @Size(min = 10, max = 4000) String justification) {
    }

    public record SessionConfigResponse(
            UUID id, UUID brokerId, String name, SessionEnvironment environment, FixVersion fixVersion,
            FixSessionSpec.Role role, String beginString, String senderCompId, String targetCompId, String host, int port,
            int heartbeatIntervalSeconds, int reconnectIntervalSeconds, boolean resetOnLogon, boolean resetOnLogout,
            boolean resetOnDisconnect, String credentialRef, String status, String approvalStatus, UUID approvalRequestId,
            String approvedPayloadHash, String activatedBy, Instant activatedAt, String createdBy, Instant createdAt,
            Instant updatedAt, long version) {

        public static SessionConfigResponse of(SessionConfig c) {
            return new SessionConfigResponse(c.id(), c.brokerId(), c.name(), c.environment(), c.fixVersion(), c.role(),
                    c.fixVersion().beginString(), c.senderCompId(), c.targetCompId(), c.host(), c.port(),
                    c.heartbeatIntervalSeconds(), c.reconnectIntervalSeconds(), c.resetOnLogon(), c.resetOnLogout(),
                    c.resetOnDisconnect(), c.credentialRef(), c.status().name(), c.status().name(), c.approvalRequestId(),
                    c.approvedPayloadHash(), c.activatedBy(), c.activatedAt(), c.createdBy(), c.createdAt(), c.updatedAt(),
                    c.version());
        }
    }

    public record ValidationResponse(boolean valid, List<FixSessionSpecValidator.Violation> violations) {
    }
}
