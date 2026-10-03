package com.fixai.platform.broker.adapter.out.persistence;

import com.fixai.platform.broker.application.port.outbound.SessionConfigRepository;
import com.fixai.platform.broker.application.service.SessionConfigExceptions;
import com.fixai.platform.broker.domain.session.SessionConfig;
import com.fixai.platform.broker.domain.session.SessionConfigStatus;
import com.fixai.platform.broker.domain.session.SessionEnvironment;
import com.fixai.platform.fixcore.FixSessionSpec;
import com.fixai.platform.fixcore.FixVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcSessionConfigRepository implements SessionConfigRepository {

    private final JdbcClient jdbc;

    public JdbcSessionConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SessionConfig c) {
        try {
            bind(jdbc.sql("""
                    INSERT INTO session_config (id, broker_id, name, environment, fix_version, role, sender_comp_id,
                        target_comp_id, host, port, heartbeat_interval_seconds, reconnect_interval_seconds, reset_on_logon,
                        reset_on_logout, reset_on_disconnect, credential_ref, status, approval_request_id,
                        approved_payload_hash, activated_by, activated_at, created_by, created_at, updated_at, version)
                    VALUES (:id, :brokerId, :name, :environment, :fixVersion, :role, :sender, :target, :host, :port,
                        :heartbeat, :reconnect, :resetOnLogon, :resetOnLogout, :resetOnDisconnect, :credentialRef, :status,
                        :approvalId, :payloadHash, :activatedBy, :activatedAt, :createdBy, :createdAt, :updatedAt, :version)
                    """), c).update();
        } catch (DuplicateKeyException exception) {
            throw duplicate();
        }
    }

    @Override
    public boolean update(SessionConfig c, long expectedVersion, SessionConfigStatus expectedStatus) {
        try {
            return bind(jdbc.sql("""
                    UPDATE session_config SET name = :name, environment = :environment, fix_version = :fixVersion,
                        role = :role, sender_comp_id = :sender, target_comp_id = :target, host = :host, port = :port,
                        heartbeat_interval_seconds = :heartbeat, reconnect_interval_seconds = :reconnect,
                        reset_on_logon = :resetOnLogon, reset_on_logout = :resetOnLogout,
                        reset_on_disconnect = :resetOnDisconnect, credential_ref = :credentialRef, status = :status,
                        approval_request_id = :approvalId, approved_payload_hash = :payloadHash,
                        activated_by = :activatedBy, activated_at = :activatedAt, updated_at = :updatedAt, version = :version
                    WHERE id = :id AND version = :expectedVersion AND status = :expectedStatus
                    """), c)
                    .param("expectedVersion", expectedVersion)
                    .param("expectedStatus", expectedStatus.name())
                    .update() == 1;
        } catch (DuplicateKeyException exception) {
            throw duplicate();
        }
    }

    @Override
    public Optional<SessionConfig> find(UUID id) {
        return jdbc.sql("SELECT * FROM session_config WHERE id = :id").param("id", id)
                .query(JdbcSessionConfigRepository::map).optional();
    }

    @Override
    public List<SessionConfig> findByBroker(UUID brokerId) {
        return jdbc.sql("SELECT * FROM session_config WHERE broker_id = :id ORDER BY created_at DESC")
                .param("id", brokerId).query(JdbcSessionConfigRepository::map).list();
    }

    @Override
    public boolean duplicateExists(SessionConfig c) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM session_config
                WHERE environment = :environment AND fix_version = :fixVersion AND sender_comp_id = :sender
                  AND target_comp_id = :target AND status <> 'RETIRED' AND id <> :id
                """)
                .param("environment", c.environment().name()).param("fixVersion", c.fixVersion().name())
                .param("sender", c.senderCompId()).param("target", c.targetCompId()).param("id", c.id())
                .query(Long.class).single() > 0;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, SessionConfig c) {
        return spec.param("id", c.id()).param("brokerId", c.brokerId()).param("name", c.name())
                .param("environment", c.environment().name()).param("fixVersion", c.fixVersion().name())
                .param("role", c.role().name()).param("sender", c.senderCompId()).param("target", c.targetCompId())
                .param("host", c.host()).param("port", c.port()).param("heartbeat", c.heartbeatIntervalSeconds())
                .param("reconnect", c.reconnectIntervalSeconds()).param("resetOnLogon", c.resetOnLogon())
                .param("resetOnLogout", c.resetOnLogout()).param("resetOnDisconnect", c.resetOnDisconnect())
                .param("credentialRef", c.credentialRef()).param("status", c.status().name())
                .param("approvalId", c.approvalRequestId()).param("payloadHash", c.approvedPayloadHash())
                .param("activatedBy", c.activatedBy()).param("activatedAt", ts(c.activatedAt()))
                .param("createdBy", c.createdBy()).param("createdAt", ts(c.createdAt()))
                .param("updatedAt", ts(c.updatedAt())).param("version", c.version());
    }

    private static SessionConfig map(ResultSet rs, int row) throws SQLException {
        return new SessionConfig(rs.getObject("id", UUID.class), rs.getObject("broker_id", UUID.class), rs.getString("name"),
                SessionEnvironment.valueOf(rs.getString("environment")), FixVersion.valueOf(rs.getString("fix_version")),
                FixSessionSpec.Role.valueOf(rs.getString("role")), rs.getString("sender_comp_id"),
                rs.getString("target_comp_id"), rs.getString("host"), rs.getInt("port"),
                rs.getInt("heartbeat_interval_seconds"), rs.getInt("reconnect_interval_seconds"),
                rs.getBoolean("reset_on_logon"), rs.getBoolean("reset_on_logout"), rs.getBoolean("reset_on_disconnect"),
                rs.getString("credential_ref"), SessionConfigStatus.valueOf(rs.getString("status")),
                rs.getObject("approval_request_id", UUID.class), rs.getString("approved_payload_hash"),
                rs.getString("activated_by"), instant(rs, "activated_at"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static SessionConfigExceptions.Conflict duplicate() {
        return new SessionConfigExceptions.Conflict("A configuration with the same environment, FIX version and CompIDs exists",
                List.of("DUPLICATE_SESSION"));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
