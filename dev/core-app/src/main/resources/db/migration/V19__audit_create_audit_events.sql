-- Audit module (SPEC §4, FR-A-1, M10): the hash-chained audit trail.
-- Every state-changing action appends one row; hash = SHA-256(prev_hash || canonical JSON of the row's content),
-- so changing, removing or reordering any row breaks every hash after it (ADR 0008). The first row's prev_hash
-- is the genesis hash: 64 zeros.
-- seq orders the chain. It is not part of the hash: a rolled-back writer leaves a gap in the sequence, and
-- prev_hash already fixes each row's position.

CREATE TABLE audit.audit_events (
    seq            bigserial    PRIMARY KEY,
    occurred_at    timestamptz  NOT NULL,
    -- Our user ID (identity.users.id) of the caller; NULL for SYSTEM (AML service, scheduled jobs, async listeners).
    actor_id       uuid,
    actor_role     varchar(32)  NOT NULL,
    action         varchar(64)  NOT NULL,
    entity_type    varchar(32)  NOT NULL,
    entity_id      uuid         NOT NULL,
    -- State before and after the action, masked by the application (FR-PR-1). jsonb normalises key order and
    -- whitespace; the hash is computed over the application's canonical form, which is reproducible from it.
    before         jsonb,
    after          jsonb,
    -- Text, not inet: inet would rewrite the address (e.g. IPv6 compression) and the stored value must be the
    -- hashed one.
    ip             varchar(64),
    correlation_id varchar(64),
    prev_hash      char(64)     NOT NULL,
    hash           char(64)     NOT NULL,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT ck_audit_events_actor_role
        CHECK (actor_role IN ('CUSTOMER', 'COMPLIANCE_OFFICER', 'ADMIN', 'AUDITOR', 'SYSTEM')),
    -- Only SYSTEM may be anonymous; a SYSTEM row may still name a user (authenticated, but no application role).
    CONSTRAINT ck_audit_events_actor CHECK (actor_id IS NOT NULL OR actor_role = 'SYSTEM'),
    CONSTRAINT ck_audit_events_action CHECK (action ~ '^[A-Z_]+$'),
    CONSTRAINT ck_audit_events_entity_type CHECK (entity_type ~ '^[A-Z_]+$'),
    CONSTRAINT ck_audit_events_prev_hash CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_audit_events_hash CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uk_audit_events_hash UNIQUE (hash),
    -- The chain is linear: no two rows extend the same predecessor (a fork), and genesis starts it once.
    CONSTRAINT uk_audit_events_prev_hash UNIQUE (prev_hash)
);

-- GET /audit/events?entityType=&entityId= (Day 20).
CREATE INDEX ix_audit_events_entity ON audit.audit_events (entity_type, entity_id, seq);

-- FR-A-1: append-only. The database refuses UPDATE, DELETE and TRUNCATE, even from raw SQL.
CREATE FUNCTION audit.reject_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_events
    FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_mutation();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit.audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_audit_mutation();
