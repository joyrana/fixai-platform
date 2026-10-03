-- Certification service schema (owned exclusively by certification-service).
-- Evidence and step results are append-only: rows are inserted once when a scenario finishes and never updated.

CREATE TABLE certification_run (
    id                  UUID PRIMARY KEY,
    suite_id            VARCHAR(100),
    scenario_ids        JSONB        NOT NULL,
    fix_version         VARCHAR(16)  NOT NULL,
    target_type         VARCHAR(32)  NOT NULL,
    target_host         VARCHAR(255) NOT NULL,
    target_port         INT          NOT NULL,
    target_comp_id      VARCHAR(64)  NOT NULL,
    sender_comp_id      VARCHAR(64)  NOT NULL,
    simulator_profile   VARCHAR(64),
    session_config_id   UUID,
    environment         VARCHAR(32)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    verdict             VARCHAR(16),
    requested_by        VARCHAR(128) NOT NULL,
    correlation_id      VARCHAR(128) NOT NULL,
    idempotency_key     VARCHAR(128) UNIQUE,
    request_hash        CHAR(64)     NOT NULL,
    catalogue_hash      CHAR(64)     NOT NULL,
    engine_version      VARCHAR(32)  NOT NULL,
    evidence_digest     CHAR(64),
    scenarios_total     INT          NOT NULL,
    scenarios_passed    INT          NOT NULL DEFAULT 0,
    scenarios_failed    INT          NOT NULL DEFAULT 0,
    scenarios_errored   INT          NOT NULL DEFAULT 0,
    error_detail        TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT chk_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'ERROR')),
    CONSTRAINT chk_run_verdict CHECK (verdict IS NULL OR verdict IN ('PASSED', 'FAILED', 'INCONCLUSIVE'))
);
CREATE INDEX idx_run_created_at ON certification_run (created_at DESC);
CREATE INDEX idx_run_status ON certification_run (status);

CREATE TABLE scenario_execution (
    id                UUID PRIMARY KEY,
    run_id            UUID         NOT NULL REFERENCES certification_run (id) ON DELETE CASCADE,
    position          INT          NOT NULL,
    scenario_id       VARCHAR(64)  NOT NULL,
    scenario_version  INT          NOT NULL,
    title             VARCHAR(255) NOT NULL,
    category          VARCHAR(16)  NOT NULL,
    mandatory         BOOLEAN      NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    sender_comp_id    VARCHAR(64)  NOT NULL,
    target_comp_id    VARCHAR(64)  NOT NULL,
    id_prefix         VARCHAR(64)  NOT NULL,
    failure_summary   TEXT,
    protocol_checks   JSONB        NOT NULL,
    evidence_count    BIGINT       NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_execution_position UNIQUE (run_id, position)
);

CREATE TABLE step_result (
    id                     UUID PRIMARY KEY,
    scenario_execution_id  UUID        NOT NULL REFERENCES scenario_execution (id) ON DELETE CASCADE,
    step_index             INT         NOT NULL,
    step_type              VARCHAR(32) NOT NULL,
    description            TEXT        NOT NULL,
    status                 VARCHAR(16) NOT NULL,
    anchor_ordinal         BIGINT,
    window_end_ordinal     BIGINT,
    matched_ordinal        BIGINT,
    consumed_ordinals      JSONB       NOT NULL,
    assertions             JSONB       NOT NULL,
    captures               JSONB       NOT NULL,
    detail                 TEXT,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    CONSTRAINT uq_step_index UNIQUE (scenario_execution_id, step_index)
);

CREATE TABLE evidence (
    id                     UUID PRIMARY KEY,
    run_id                 UUID        NOT NULL REFERENCES certification_run (id) ON DELETE CASCADE,
    scenario_execution_id  UUID        NOT NULL REFERENCES scenario_execution (id) ON DELETE CASCADE,
    ordinal                BIGINT      NOT NULL,
    kind                   VARCHAR(8)  NOT NULL,
    direction              VARCHAR(8),
    msg_type               VARCHAR(8),
    msg_seq_num            INT,
    occurred_at            TIMESTAMPTZ NOT NULL,
    raw_redacted           TEXT,
    fields                 JSONB,
    sha256                 CHAR(64),
    event_text             TEXT,
    schema_version         INT         NOT NULL DEFAULT 1,
    CONSTRAINT uq_evidence_ordinal UNIQUE (scenario_execution_id, ordinal)
);
CREATE INDEX idx_evidence_run ON evidence (run_id);
CREATE INDEX idx_evidence_msg_type ON evidence (scenario_execution_id, msg_type);
