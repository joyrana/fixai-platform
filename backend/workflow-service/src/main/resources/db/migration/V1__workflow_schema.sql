-- Workflow service schema: approvals bound to payload hashes, and the append-only, hash-chained audit log.

CREATE TABLE approval_request (
    id                  UUID PRIMARY KEY,
    action              VARCHAR(64)  NOT NULL,
    target_type         VARCHAR(64)  NOT NULL,
    target_id           VARCHAR(128) NOT NULL,
    environment         VARCHAR(16)  NOT NULL,
    arguments           JSONB        NOT NULL,
    payload_hash        CHAR(64)     NOT NULL,
    justification       TEXT         NOT NULL,
    requested_by        VARCHAR(128) NOT NULL,
    requester_type      VARCHAR(16)  NOT NULL,
    risk_level          VARCHAR(16)  NOT NULL,
    evidence_refs       JSONB        NOT NULL,
    trace_ids           JSONB        NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    policy_version      VARCHAR(64)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    decided_by          VARCHAR(128),
    decided_at          TIMESTAMPTZ,
    decision_rationale  TEXT,
    consumed_by         VARCHAR(128),
    consumed_at         TIMESTAMPTZ,
    correlation_id      VARCHAR(128),
    idempotency_key     VARCHAR(128),
    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CHANGES_REQUESTED','CANCELLED','EXPIRED','CONSUMED')),
    CONSTRAINT chk_four_eyes CHECK (decided_by IS NULL OR status = 'CANCELLED' OR decided_by <> requested_by),
    CONSTRAINT uq_approval_idempotency UNIQUE (requested_by, idempotency_key)
);
CREATE INDEX idx_approval_status ON approval_request (status, created_at DESC);
CREATE INDEX idx_approval_target ON approval_request (target_type, target_id);

CREATE TABLE approval_decision (
    id              BIGSERIAL PRIMARY KEY,
    approval_id     UUID         NOT NULL REFERENCES approval_request (id),
    decision        VARCHAR(24)  NOT NULL,
    actor           VARCHAR(128) NOT NULL,
    rationale       TEXT         NOT NULL,
    policy_version  VARCHAR(64)  NOT NULL,
    decided_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_decision_approval ON approval_decision (approval_id, id);

CREATE TABLE audit_event (
    sequence        BIGINT PRIMARY KEY,
    id              UUID         NOT NULL UNIQUE,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    actor           VARCHAR(128) NOT NULL,
    actor_type      VARCHAR(16)  NOT NULL,
    recorded_by     VARCHAR(128) NOT NULL,
    action          VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(64)  NOT NULL,
    resource_id     VARCHAR(128) NOT NULL,
    correlation_id  VARCHAR(128),
    outcome         VARCHAR(32)  NOT NULL,
    details         JSONB        NOT NULL,
    previous_hash   CHAR(64)     NOT NULL,
    hash            CHAR(64)     NOT NULL UNIQUE
);
CREATE INDEX idx_audit_resource ON audit_event (resource_type, resource_id, sequence);
CREATE INDEX idx_audit_action ON audit_event (action, sequence);

-- The audit log and decision history are append-only at the database level.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER approval_decision_append_only BEFORE UPDATE OR DELETE ON approval_decision
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
