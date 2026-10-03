package com.fixai.platform.certification.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixai.platform.certification.application.port.out.RunRepository;
import com.fixai.platform.certification.domain.evaluation.AssertionResult;
import com.fixai.platform.certification.domain.evidence.EvidenceRecord;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.RunStatus;
import com.fixai.platform.certification.domain.run.RunTarget;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.certification.domain.run.ScenarioOutcome;
import com.fixai.platform.certification.domain.run.ScenarioStatus;
import com.fixai.platform.certification.domain.run.StepResult;
import com.fixai.platform.certification.domain.run.StepStatus;
import com.fixai.platform.certification.domain.run.Verdict;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.fixcore.FixField;
import com.fixai.platform.fixcore.FixMessageView;
import com.fixai.platform.fixcore.FixVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL adapter using explicit SQL. Scenario outcomes (execution, steps, evidence) are written in one transaction
 * with batched evidence inserts.
 */
public class JdbcRunRepository implements RunRepository {

    private static final TypeReference<List<AssertionResult>> ASSERTIONS = new TypeReference<>() { };
    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<FixField>> FIELDS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcRunRepository(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public void create(CertificationRun run) {
        jdbc.sql("""
                INSERT INTO certification_run (id, suite_id, scenario_ids, fix_version, target_type, target_host, target_port,
                    target_comp_id, sender_comp_id, simulator_profile, session_config_id, environment, status, requested_by,
                    correlation_id, idempotency_key, request_hash, catalogue_hash, engine_version, scenarios_total, created_at)
                VALUES (:id, :suiteId, CAST(:scenarioIds AS JSONB), :fixVersion, :targetType, :host, :port, :targetCompId,
                    :senderCompId, :profile, :sessionConfigId, :environment, :status, :requestedBy, :correlationId,
                    :idempotencyKey, :requestHash, :catalogueHash, :engineVersion, :total, :createdAt)
                """)
                .param("id", run.id())
                .param("suiteId", run.suiteId())
                .param("scenarioIds", write(run.scenarioIds()))
                .param("fixVersion", run.fixVersion().name())
                .param("targetType", run.target().type().name())
                .param("host", run.target().host())
                .param("port", run.target().port())
                .param("targetCompId", run.target().targetCompId())
                .param("senderCompId", run.senderCompId())
                .param("profile", run.target().simulatorProfile())
                .param("sessionConfigId", run.target().sessionConfigId())
                .param("environment", run.target().environment())
                .param("status", run.status().name())
                .param("requestedBy", run.requestedBy())
                .param("correlationId", run.correlationId())
                .param("idempotencyKey", run.idempotencyKey())
                .param("requestHash", run.requestHash())
                .param("catalogueHash", run.catalogueHash())
                .param("engineVersion", run.engineVersion())
                .param("total", run.scenariosTotal())
                .param("createdAt", ts(run.createdAt()))
                .update();
    }

    @Override
    public Optional<CertificationRun> find(UUID runId) {
        return jdbc.sql("SELECT * FROM certification_run WHERE id = :id").param("id", runId).query(this::run).optional();
    }

    @Override
    public Optional<CertificationRun> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM certification_run WHERE idempotency_key = :key").param("key", key).query(this::run).optional();
    }

    @Override
    public List<CertificationRun> list(int page, int size) {
        return jdbc.sql("SELECT * FROM certification_run ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(this::run)
                .list();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM certification_run").query(Long.class).single();
    }

    @Override
    public boolean markRunning(UUID runId, Instant startedAt) {
        return jdbc.sql("UPDATE certification_run SET status = 'RUNNING', started_at = :at WHERE id = :id AND status = 'QUEUED'")
                .param("id", runId).param("at", ts(startedAt)).update() == 1;
    }

    @Override
    public UUID saveScenarioOutcome(UUID runId, int position, Scenario scenario, String idPrefix, ScenarioOutcome outcome) {
        UUID executionId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO scenario_execution (id, run_id, position, scenario_id, scenario_version, title, category,
                        mandatory, status, sender_comp_id, target_comp_id, id_prefix, failure_summary, protocol_checks,
                        evidence_count, started_at, completed_at)
                    VALUES (:id, :runId, :position, :scenarioId, :version, :title, :category, :mandatory, :status,
                        :sender, :target, :idPrefix, :failure, CAST(:checks AS JSONB), :evidenceCount, :startedAt, :completedAt)
                    """)
                    .param("id", executionId)
                    .param("runId", runId)
                    .param("position", position)
                    .param("scenarioId", scenario.id())
                    .param("version", scenario.version())
                    .param("title", scenario.title())
                    .param("category", scenario.category().name())
                    .param("mandatory", scenario.mandatory())
                    .param("status", outcome.status().name())
                    .param("sender", outcome.senderCompId())
                    .param("target", outcome.targetCompId())
                    .param("idPrefix", idPrefix)
                    .param("failure", outcome.failureSummary())
                    .param("checks", write(outcome.protocolChecks()))
                    .param("evidenceCount", outcome.evidence().size())
                    .param("startedAt", ts(outcome.startedAt()))
                    .param("completedAt", ts(outcome.completedAt()))
                    .update();

            template.batchUpdate("""
                    INSERT INTO step_result (id, scenario_execution_id, step_index, step_type, description, status,
                        anchor_ordinal, window_end_ordinal, matched_ordinal, consumed_ordinals, assertions, captures, detail,
                        started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?)
                    """, outcome.steps(), 200, (ps, step) -> {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, executionId);
                        ps.setInt(3, step.index());
                        ps.setString(4, step.type());
                        ps.setString(5, step.description());
                        ps.setString(6, step.status().name());
                        ps.setObject(7, step.anchorOrdinal());
                        ps.setObject(8, step.windowEndOrdinal());
                        ps.setObject(9, step.matchedOrdinal());
                        ps.setString(10, write(step.consumedOrdinals()));
                        ps.setString(11, write(step.assertions()));
                        ps.setString(12, write(step.captures()));
                        ps.setString(13, step.detail());
                        ps.setTimestamp(14, ts(step.startedAt()));
                        ps.setTimestamp(15, ts(step.completedAt()));
                    });

            template.batchUpdate("""
                    INSERT INTO evidence (id, run_id, scenario_execution_id, ordinal, kind, direction, msg_type, msg_seq_num,
                        occurred_at, raw_redacted, fields, sha256, event_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                    """, outcome.evidence(), 500, (ps, record) -> {
                        FixMessageView message = record.message();
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, runId);
                        ps.setObject(3, executionId);
                        ps.setLong(4, record.ordinal());
                        ps.setString(5, record.kind().name());
                        ps.setString(6, record.direction() == null ? null : record.direction().name());
                        ps.setString(7, message == null ? null : message.msgType());
                        ps.setObject(8, message == null ? null : message.msgSeqNum());
                        ps.setTimestamp(9, ts(record.occurredAt()));
                        ps.setString(10, message == null ? null : message.rawRedacted());
                        ps.setString(11, message == null ? null : write(message.fields()));
                        ps.setString(12, message == null ? null : message.sha256());
                        ps.setString(13, record.eventText());
                    });
        });
        return executionId;
    }

    @Override
    public void complete(UUID runId, RunStatus status, Verdict verdict, int passed, int failed, int errored,
                         String evidenceDigest, String errorDetail, Instant completedAt) {
        jdbc.sql("""
                UPDATE certification_run SET status = :status, verdict = :verdict, scenarios_passed = :passed,
                    scenarios_failed = :failed, scenarios_errored = :errored, evidence_digest = :digest,
                    error_detail = :error, completed_at = :completedAt
                WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                """)
                .param("id", runId)
                .param("status", status.name())
                .param("verdict", verdict == null ? null : verdict.name())
                .param("passed", passed)
                .param("failed", failed)
                .param("errored", errored)
                .param("digest", evidenceDigest)
                .param("error", errorDetail)
                .param("completedAt", ts(completedAt))
                .update();
    }

    @Override
    public List<ScenarioExecution> scenarioExecutions(UUID runId) {
        return jdbc.sql("SELECT * FROM scenario_execution WHERE run_id = :id ORDER BY position")
                .param("id", runId).query(this::execution).list();
    }

    @Override
    public Optional<ScenarioExecution> scenarioExecution(UUID executionId) {
        return jdbc.sql("SELECT * FROM scenario_execution WHERE id = :id").param("id", executionId).query(this::execution).optional();
    }

    @Override
    public List<StepResult> steps(UUID executionId) {
        return jdbc.sql("SELECT * FROM step_result WHERE scenario_execution_id = :id ORDER BY step_index")
                .param("id", executionId)
                .query((rs, row) -> new StepResult(
                        rs.getInt("step_index"),
                        rs.getString("step_type"),
                        rs.getString("description"),
                        StepStatus.valueOf(rs.getString("status")),
                        nullableLong(rs, "anchor_ordinal"),
                        nullableLong(rs, "window_end_ordinal"),
                        nullableLong(rs, "matched_ordinal"),
                        read(rs.getString("consumed_ordinals"), LONGS),
                        read(rs.getString("assertions"), ASSERTIONS),
                        read(rs.getString("captures"), STRING_MAP),
                        rs.getString("detail"),
                        instant(rs, "started_at"),
                        instant(rs, "completed_at")))
                .list();
    }

    @Override
    public List<EvidenceRecord> evidence(UUID executionId) {
        return jdbc.sql("SELECT * FROM evidence WHERE scenario_execution_id = :id ORDER BY ordinal")
                .param("id", executionId)
                .query((rs, row) -> {
                    EvidenceRecord.Kind kind = EvidenceRecord.Kind.valueOf(rs.getString("kind"));
                    String direction = rs.getString("direction");
                    FixMessageView view = null;
                    if (kind == EvidenceRecord.Kind.MESSAGE) {
                        List<FixField> fields = read(rs.getString("fields"), FIELDS);
                        view = new FixMessageView(
                                value(fields, 8), rs.getString("msg_type"), (Integer) rs.getObject("msg_seq_num"),
                                value(fields, 49), value(fields, 56), fields, rs.getString("raw_redacted"), rs.getString("sha256"));
                    }
                    return new EvidenceRecord(rs.getLong("ordinal"), kind,
                            direction == null ? null : EvidenceRecord.Direction.valueOf(direction),
                            instant(rs, "occurred_at"), view, rs.getString("event_text"));
                })
                .list();
    }

    @Override
    public List<String> evidenceHashes(UUID runId) {
        return jdbc.sql("""
                SELECT e.sha256 FROM evidence e JOIN scenario_execution s ON s.id = e.scenario_execution_id
                WHERE e.run_id = :id AND e.sha256 IS NOT NULL ORDER BY s.position, e.ordinal
                """).param("id", runId).query(String.class).list();
    }

    @Override
    public int failInterruptedRuns(String reason, Instant at) {
        return jdbc.sql("""
                UPDATE certification_run SET status = 'ERROR', verdict = 'INCONCLUSIVE', error_detail = :reason, completed_at = :at
                WHERE status IN ('QUEUED', 'RUNNING')
                """).param("reason", reason).param("at", ts(at)).update();
    }

    private CertificationRun run(ResultSet rs, int row) throws SQLException {
        String verdict = rs.getString("verdict");
        String sessionConfigId = rs.getString("session_config_id");
        RunTarget target = new RunTarget(RunTarget.Type.valueOf(rs.getString("target_type")), rs.getString("target_host"),
                rs.getInt("target_port"), rs.getString("target_comp_id"), rs.getString("simulator_profile"),
                sessionConfigId == null ? null : UUID.fromString(sessionConfigId), rs.getString("environment"));
        return new CertificationRun(
                rs.getObject("id", UUID.class),
                rs.getString("suite_id"),
                read(rs.getString("scenario_ids"), STRINGS),
                FixVersion.valueOf(rs.getString("fix_version")),
                target,
                rs.getString("sender_comp_id"),
                RunStatus.valueOf(rs.getString("status")),
                verdict == null ? null : Verdict.valueOf(verdict),
                rs.getString("requested_by"),
                rs.getString("correlation_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("catalogue_hash"),
                rs.getString("engine_version"),
                rs.getString("evidence_digest"),
                rs.getInt("scenarios_total"),
                rs.getInt("scenarios_passed"),
                rs.getInt("scenarios_failed"),
                rs.getInt("scenarios_errored"),
                rs.getString("error_detail"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"));
    }

    private ScenarioExecution execution(ResultSet rs, int row) throws SQLException {
        return new ScenarioExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("position"),
                rs.getString("scenario_id"),
                rs.getInt("scenario_version"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getBoolean("mandatory"),
                ScenarioStatus.valueOf(rs.getString("status")),
                rs.getString("sender_comp_id"),
                rs.getString("target_comp_id"),
                rs.getString("id_prefix"),
                rs.getString("failure_summary"),
                read(rs.getString("protocol_checks"), ASSERTIONS),
                rs.getLong("evidence_count"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"));
    }

    private static String value(List<FixField> fields, int tag) {
        return fields.stream().filter(f -> f.tag() == tag).map(FixField::value).findFirst().orElse(null);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialise " + value.getClass().getSimpleName(), exception);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corrupt JSON column", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
