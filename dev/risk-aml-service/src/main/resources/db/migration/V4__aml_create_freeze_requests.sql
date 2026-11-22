-- FR-AML-3: the outbox for AccountFreezeRequestedV1 (ADR 0007 "Freeze requests").
-- A HIGH alert inserts its row in the same transaction as the alert; FreezeRequestRelay sends pending rows to
-- Kafka after the commit and sets published_at. The row's id is the message's eventId, so a re-send after a crash
-- between the send and the update carries the same eventId and core-app skips it.
CREATE TABLE aml.freeze_requests (
    id             uuid        PRIMARY KEY,
    alert_id       uuid        NOT NULL REFERENCES aml.alerts (id),
    case_id        uuid        NOT NULL REFERENCES aml.cases (id),
    wallet_id      uuid        NOT NULL,
    rule_code      varchar(2)  NOT NULL,
    -- From the consumed record's header (already checked to be a plain token), so the freeze can be traced.
    correlation_id varchar(64),
    requested_at   timestamptz NOT NULL,
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    -- One request per HIGH alert.
    CONSTRAINT uq_freeze_requests_alert UNIQUE (alert_id),
    CONSTRAINT ck_freeze_requests_rule_code CHECK (rule_code IN ('R1', 'R2', 'R3', 'R4')),
    CONSTRAINT ck_freeze_requests_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_freeze_requests_published_after CHECK (published_at IS NULL OR published_at >= requested_at)
);

-- The relay's query: oldest pending first. Partial, so it stays as small as the backlog.
CREATE INDEX ix_freeze_requests_pending ON aml.freeze_requests (requested_at) WHERE published_at IS NULL;
CREATE INDEX ix_freeze_requests_case ON aml.freeze_requests (case_id);
