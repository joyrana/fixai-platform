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
