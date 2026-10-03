package com.fixai.platform.workflow.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixai.platform.workflow.application.port.AuditRepository;
import com.fixai.platform.workflow.domain.audit.AuditEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends are serialised with a transaction-scoped advisory lock so each event links to exactly one predecessor.
 * Timestamps are truncated to microseconds (PostgreSQL precision) before hashing so stored and recomputed hashes agree.
 */
public class JdbcAuditRepository implements AuditRepository {

    private static final long CHAIN_LOCK = 0x46495841_49415544L; // "FIXAIAUD"
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcAuditRepository(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public AuditEvent append(UUID id, Instant occurredAt, String actor, String actorType, String recordedBy, String action,
                             String resourceType, String resourceId, String correlationId, String outcome, Map<String, String> details) {
        Instant at = occurredAt.truncatedTo(ChronoUnit.MICROS);
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lock)").param("lock", CHAIN_LOCK).query((rs, row) -> 1).list();
            List<Object[]> head = jdbc.sql("SELECT sequence, hash FROM audit_event ORDER BY sequence DESC LIMIT 1")
                    .query((rs, row) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();
            long sequence = head.isEmpty() ? 1 : (long) head.get(0)[0] + 1;
            String previous = head.isEmpty() ? AuditEvent.GENESIS : (String) head.get(0)[1];
            String hash = AuditEvent.computeHash(sequence, id, at, actor, actorType, recordedBy, action, resourceType,
                    resourceId, correlationId, outcome, details, previous);
            jdbc.sql("""
                    INSERT INTO audit_event (sequence, id, occurred_at, actor, actor_type, recorded_by, action, resource_type,
                        resource_id, correlation_id, outcome, details, previous_hash, hash)
                    VALUES (:sequence, :id, :at, :actor, :actorType, :recordedBy, :action, :resourceType, :resourceId,
                        :correlationId, :outcome, CAST(:details AS JSONB), :previous, :hash)
                    """)
                    .param("sequence", sequence).param("id", id).param("at", Timestamp.from(at)).param("actor", actor)
                    .param("actorType", actorType).param("recordedBy", recordedBy).param("action", action)
                    .param("resourceType", resourceType).param("resourceId", resourceId).param("correlationId", correlationId)
                    .param("outcome", outcome).param("details", write(details)).param("previous", previous).param("hash", hash)
                    .update();
            return new AuditEvent(sequence, id, at, actor, actorType, recordedBy, action, resourceType, resourceId,
                    correlationId, outcome, details, previous, hash);
        });
    }

    @Override
    public List<AuditEvent> list(String resourceType, String resourceId, String action, int page, int size) {
        return jdbc.sql("""
                SELECT * FROM audit_event
                WHERE (CAST(:type AS VARCHAR) IS NULL OR resource_type = :type)
                  AND (CAST(:rid AS VARCHAR) IS NULL OR resource_id = :rid)
                  AND (CAST(:action AS VARCHAR) IS NULL OR action = :action)
                ORDER BY sequence DESC LIMIT :limit OFFSET :offset
                """)
                .param("type", resourceType).param("rid", resourceId).param("action", action)
                .param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
    }

    @Override
    public long count(String resourceType, String resourceId, String action) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM audit_event
                WHERE (CAST(:type AS VARCHAR) IS NULL OR resource_type = :type)
                  AND (CAST(:rid AS VARCHAR) IS NULL OR resource_id = :rid)
                  AND (CAST(:action AS VARCHAR) IS NULL OR action = :action)
                """)
                .param("type", resourceType).param("rid", resourceId).param("action", action)
                .query(Long.class).single();
    }

    @Override
    public void forEachInOrder(Consumer<AuditEvent> consumer) {
        template.query("SELECT * FROM audit_event ORDER BY sequence", rs -> {
            consumer.accept(map(rs, 0));
        });
    }

    private AuditEvent map(ResultSet rs, int row) throws SQLException {
        return new AuditEvent(rs.getLong("sequence"), rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"), rs.getString("actor_type"),
                rs.getString("recorded_by"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("correlation_id"), rs.getString("outcome"),
                read(rs.getString("details")), rs.getString("previous_hash"), rs.getString("hash"));
    }

    private String write(Map<String, String> details) {
        try {
            return json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, String> read(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corrupt audit details", exception);
        }
    }
}
