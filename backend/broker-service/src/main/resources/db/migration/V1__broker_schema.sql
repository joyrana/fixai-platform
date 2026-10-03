-- Broker service schema: broker profiles and FIX session configurations (owned exclusively by broker-service).

CREATE TABLE broker (
    id           UUID PRIMARY KEY,
    broker_code  VARCHAR(40)  NOT NULL,
    name         VARCHAR(120) NOT NULL,
    endpoint     VARCHAR(255) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_broker_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);
CREATE UNIQUE INDEX uq_broker_code ON broker (LOWER(broker_code));

CREATE TABLE session_config (
    id                          UUID PRIMARY KEY,
    broker_id                   UUID         NOT NULL REFERENCES broker (id) ON DELETE RESTRICT,
    name                        VARCHAR(120) NOT NULL,
    environment                 VARCHAR(16)  NOT NULL,
    fix_version                 VARCHAR(16)  NOT NULL,
    role                        VARCHAR(16)  NOT NULL,
    sender_comp_id              VARCHAR(64)  NOT NULL,
    target_comp_id              VARCHAR(64)  NOT NULL,
    host                        VARCHAR(253) NOT NULL,
    port                        INT          NOT NULL,
    heartbeat_interval_seconds  INT          NOT NULL,
    reconnect_interval_seconds  INT          NOT NULL,
    reset_on_logon              BOOLEAN      NOT NULL,
    reset_on_logout             BOOLEAN      NOT NULL,
    reset_on_disconnect         BOOLEAN      NOT NULL,
    credential_ref              VARCHAR(256),
    status                      VARCHAR(24)  NOT NULL,
    approval_request_id         UUID,
    approved_payload_hash       CHAR(64),
    activated_by                VARCHAR(128),
    activated_at                TIMESTAMPTZ,
    created_by                  VARCHAR(128) NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    version                     BIGINT       NOT NULL,
    CONSTRAINT chk_session_environment CHECK (environment IN ('TEST', 'UAT')),
    CONSTRAINT chk_session_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','CHANGES_REQUESTED','RETIRED')),
    CONSTRAINT chk_session_port CHECK (port BETWEEN 1 AND 65535)
);
CREATE INDEX idx_session_broker ON session_config (broker_id);
CREATE UNIQUE INDEX uq_session_identity ON session_config (environment, fix_version, sender_comp_id, target_comp_id)
    WHERE status <> 'RETIRED';

-- Transactional audit outbox (see platform-web AuditOutbox).
CREATE TABLE audit_outbox (
    id               UUID PRIMARY KEY,
    payload          JSONB       NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    delivered_at     TIMESTAMPTZ,
    last_error       TEXT
);
CREATE INDEX idx_outbox_pending ON audit_outbox (next_attempt_at) WHERE delivered_at IS NULL;
