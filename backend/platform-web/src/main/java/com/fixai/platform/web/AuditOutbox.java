package com.fixai.platform.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Transactional outbox for audit events. {@link #record} writes to the service's own {@code audit_outbox} table inside
 * the caller's transaction, so an audited change and its audit record commit or roll back together. {@link #relay}
 * delivers pending rows to workflow-service with retry and back-off; delivery is at-least-once and the event ID makes
 * duplicates detectable downstream.
 *
 * <p>Each service owns its outbox table:
 * <pre>
 * CREATE TABLE audit_outbox (id UUID PRIMARY KEY, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 *     attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL, delivered_at TIMESTAMPTZ, last_error TEXT);
 * </pre>
 */
public class AuditOutbox {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditOutbox.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final RestClient workflow;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditOutbox(JdbcClient jdbc, RestClient workflow, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.json = json;
        this.clock = clock;
    }

    public void record(String actor, Actor.Type actorType, String action, String resourceType, String resourceId,
                       String correlationId, String outcome, Map<String, String> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actor", actor);
        payload.put("actorType", actorType.name());
        payload.put("action", action);
        payload.put("resourceType", resourceType);
        payload.put("resourceId", resourceId);
        payload.put("correlationId", correlationId);
        payload.put("outcome", outcome);
        payload.put("details", details == null ? Map.of() : details);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("INSERT INTO audit_outbox (id, payload, created_at, next_attempt_at) VALUES (:id, CAST(:payload AS JSONB), :now, :now)")
                .param("id", UUID.randomUUID()).param("payload", write(payload)).param("now", now).update();
    }

    /** Delivers up to {@code batch} due events; returns the number delivered. */
    public int relay(int batch) {
        List<Map.Entry<UUID, String>> due = jdbc.sql("""
                SELECT id, payload FROM audit_outbox
                WHERE delivered_at IS NULL AND next_attempt_at <= :now
                ORDER BY created_at LIMIT :batch
                """)
                .param("now", Timestamp.from(clock.instant())).param("batch", batch)
                .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("payload"))).list();
        int delivered = 0;
        for (Map.Entry<UUID, String> entry : due) {
            try {
                workflow.post().uri("/api/v1/audit-events").contentType(MediaType.APPLICATION_JSON)
                        .body(read(entry.getValue())).retrieve().toBodilessEntity();
                jdbc.sql("UPDATE audit_outbox SET delivered_at = :now, attempts = attempts + 1 WHERE id = :id")
                        .param("now", Timestamp.from(clock.instant())).param("id", entry.getKey()).update();
                delivered++;
            } catch (RuntimeException exception) {
                jdbc.sql("""
                        UPDATE audit_outbox SET attempts = attempts + 1, last_error = :error,
                            next_attempt_at = :next WHERE id = :id
                        """)
                        .param("error", exception.getClass().getSimpleName())
                        .param("next", Timestamp.from(clock.instant().plus(backoff(entry.getKey()))))
                        .param("id", entry.getKey()).update();
                LOGGER.warn("Audit delivery failed ({}); will retry", exception.getClass().getSimpleName());
                break;
            }
        }
        return delivered;
    }

    public long pending() {
        return jdbc.sql("SELECT COUNT(*) FROM audit_outbox WHERE delivered_at IS NULL").query(Long.class).single();
    }

    private Duration backoff(UUID id) {
        int attempts = jdbc.sql("SELECT attempts FROM audit_outbox WHERE id = :id").param("id", id).query(Integer.class).single();
        return Duration.ofSeconds(Math.min(300, 1L << Math.min(attempts, 8)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
