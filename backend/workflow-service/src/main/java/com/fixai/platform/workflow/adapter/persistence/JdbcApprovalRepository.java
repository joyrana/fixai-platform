package com.fixai.platform.workflow.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixai.platform.workflow.application.port.ApprovalRepository;
import com.fixai.platform.workflow.domain.approval.ApprovalPayload;
import com.fixai.platform.workflow.domain.approval.ApprovalRequest;
import com.fixai.platform.workflow.domain.approval.ApprovalStatus;
import com.fixai.platform.workflow.domain.approval.RiskLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcApprovalRepository implements ApprovalRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcApprovalRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(ApprovalRequest r, String idempotencyKey) {
        jdbc.sql("""
                INSERT INTO approval_request (id, action, target_type, target_id, environment, arguments, payload_hash,
                    justification, requested_by, requester_type, risk_level, evidence_refs, trace_ids, status, policy_version,
                    expires_at, created_at, correlation_id, idempotency_key, filed_by)
                VALUES (:id, :action, :targetType, :targetId, :environment, CAST(:arguments AS JSONB), :hash, :justification,
                    :requestedBy, :requesterType, :risk, CAST(:evidence AS JSONB), CAST(:traces AS JSONB), :status, :policy,
                    :expiresAt, :createdAt, :correlationId, :idempotencyKey, :filedBy)
                """)
                .param("id", r.id())
                .param("action", r.payload().action())
                .param("targetType", r.payload().targetType())
                .param("targetId", r.payload().targetId())
                .param("environment", r.payload().environment())
                .param("arguments", write(r.payload().arguments()))
                .param("hash", r.payloadHash())
                .param("justification", r.justification())
                .param("requestedBy", r.requestedBy())
                .param("requesterType", r.requesterType())
                .param("risk", r.riskLevel().name())
                .param("evidence", write(r.evidenceRefs()))
                .param("traces", write(r.traceIds()))
                .param("status", r.status().name())
                .param("policy", r.policyVersion())
                .param("expiresAt", Timestamp.from(r.expiresAt()))
                .param("createdAt", Timestamp.from(r.createdAt()))
                .param("correlationId", r.correlationId())
                .param("idempotencyKey", idempotencyKey)
                .param("filedBy", r.filedBy())
                .update();
    }

    @Override
    public Optional<ApprovalRequest> find(UUID id) {
        return jdbc.sql("SELECT * FROM approval_request WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<ApprovalRequest> findByIdempotencyKey(String requestedBy, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM approval_request WHERE requested_by = :by AND idempotency_key = :key")
                .param("by", requestedBy).param("key", idempotencyKey).query(this::map).optional();
    }

    @Override
    public List<ApprovalRequest> list(ApprovalStatus status, String requestedBy, int page, int size) {
        return jdbc.sql("""
                SELECT * FROM approval_request
                WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                  AND (CAST(:by AS VARCHAR) IS NULL OR requested_by = :by OR filed_by = :by)
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """)
                .param("status", status == null ? null : status.name())
                .param("by", requestedBy)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(this::map).list();
    }

    @Override
    public long count(ApprovalStatus status, String requestedBy) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM approval_request
                WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                  AND (CAST(:by AS VARCHAR) IS NULL OR requested_by = :by OR filed_by = :by)
                """)
                .param("status", status == null ? null : status.name())
                .param("by", requestedBy)
                .query(Long.class).single();
    }

    @Override
    public boolean transition(UUID id, Set<ApprovalStatus> from, ApprovalStatus to, String actor, Instant at, String rationale) {
        return jdbc.sql("""
                UPDATE approval_request SET status = :to, decided_by = :actor, decided_at = :at, decision_rationale = :rationale
                WHERE id = :id AND status IN (:from) AND expires_at > :at
                """)
                .param("to", to.name())
                .param("actor", actor)
                .param("at", Timestamp.from(at))
                .param("rationale", rationale)
                .param("id", id)
                .param("from", from.stream().map(Enum::name).toList())
                .update() == 1;
    }

    @Override
    public boolean consume(UUID id, String consumer, Instant at) {
        return jdbc.sql("""
                UPDATE approval_request SET status = 'CONSUMED', consumed_by = :consumer, consumed_at = :at
                WHERE id = :id AND status = 'APPROVED' AND expires_at > :at
                """)
                .param("consumer", consumer).param("at", Timestamp.from(at)).param("id", id)
                .update() == 1;
    }

    @Override
    public List<UUID> expireDue(Instant now) {
        return jdbc.sql("""
                UPDATE approval_request SET status = 'EXPIRED'
                WHERE status IN ('PENDING', 'APPROVED') AND expires_at <= :now
                RETURNING id
                """)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> rs.getObject("id", UUID.class)).list();
    }

    @Override
    public void recordDecision(UUID approvalId, String decision, String actor, String rationale, String policyVersion, Instant at) {
        jdbc.sql("""
                INSERT INTO approval_decision (approval_id, decision, actor, rationale, policy_version, decided_at)
                VALUES (:id, :decision, :actor, :rationale, :policy, :at)
                """)
                .param("id", approvalId).param("decision", decision).param("actor", actor).param("rationale", rationale)
                .param("policy", policyVersion).param("at", Timestamp.from(at))
                .update();
    }

    @Override
    public List<DecisionRecord> decisions(UUID approvalId) {
        return jdbc.sql("SELECT * FROM approval_decision WHERE approval_id = :id ORDER BY id")
                .param("id", approvalId)
                .query((rs, row) -> new DecisionRecord(rs.getString("decision"), rs.getString("actor"),
                        rs.getString("rationale"), rs.getString("policy_version"), rs.getTimestamp("decided_at").toInstant()))
                .list();
    }

    private ApprovalRequest map(ResultSet rs, int row) throws SQLException {
        return new ApprovalRequest(
                rs.getObject("id", UUID.class),
                new ApprovalPayload(rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("environment"), read(rs.getString("arguments"), MAP)),
                rs.getString("payload_hash"),
                rs.getString("justification"),
                rs.getString("requested_by"),
                rs.getString("requester_type"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                read(rs.getString("evidence_refs"), STRINGS),
                read(rs.getString("trace_ids"), STRINGS),
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getString("policy_version"),
                instant(rs, "expires_at"),
                instant(rs, "created_at"),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("decision_rationale"),
                rs.getString("consumed_by"),
                instant(rs, "consumed_at"),
                rs.getString("correlation_id"),
                rs.getString("filed_by"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corrupt JSON column", exception);
        }
    }
}
