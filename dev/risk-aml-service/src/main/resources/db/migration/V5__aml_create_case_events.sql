-- FR-AML-4 / FR-PR-2: the outbox for AmlCaseStatusChangedV1 (ADR 0007). Opening a case (rule engine) or finishing it
-- (officer: close as false positive, escalate) inserts a row in the same transaction; CaseEventRelay sends pending
-- rows to Kafka after the commit and sets published_at. core-app keeps its own list of open cases from these events,
-- so erasure can be refused while a case is open. The row's id is the message's eventId: a re-send after a crash
-- between the send and the update carries the same eventId.
CREATE TABLE aml.case_events (
    id             uuid        PRIMARY KEY,
    case_id        uuid        NOT NULL REFERENCES aml.cases (id),
    wallet_id      uuid        NOT NULL,
    status         varchar(32) NOT NULL,
    correlation_id varchar(64),
    occurred_at    timestamptz NOT NULL,
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    -- Only the lifecycle steps core-app needs: opened, and the two ways a case finishes.
    CONSTRAINT ck_case_events_status CHECK (status IN ('OPEN', 'CLOSED_FALSE_POSITIVE', 'ESCALATED')),
    -- A case opens once and finishes once.
    CONSTRAINT uq_case_events_case_status UNIQUE (case_id, status),
    CONSTRAINT ck_case_events_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_case_events_published_after CHECK (published_at IS NULL OR published_at >= occurred_at)
);

-- The relay's query: oldest pending first. Partial, so it stays as small as the backlog.
CREATE INDEX ix_case_events_pending ON aml.case_events (occurred_at) WHERE published_at IS NULL;
