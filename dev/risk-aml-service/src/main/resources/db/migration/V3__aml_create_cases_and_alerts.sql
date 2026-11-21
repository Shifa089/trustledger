-- FR-AML-2 and FR-AML-4. Tables only for now; the rule engine and the case workflow fill them in the next session.
CREATE TABLE aml.cases (
    id          uuid        PRIMARY KEY,
    wallet_id   uuid        NOT NULL,
    status      varchar(24) NOT NULL,
    assigned_to uuid,
    resolution  text,
    ai_summary  text,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_cases_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'CLOSED_FALSE_POSITIVE', 'ESCALATED')),
    -- A finished case says why; an open one doesn't yet.
    CONSTRAINT ck_cases_resolution CHECK (
        (status IN ('CLOSED_FALSE_POSITIVE', 'ESCALATED')) = (resolution IS NOT NULL))
);

-- One open case per wallet (SPEC §4): new alerts join it instead of opening another.
CREATE UNIQUE INDEX uq_cases_one_open_per_wallet ON aml.cases (wallet_id)
    WHERE status IN ('OPEN', 'INVESTIGATING');

CREATE TABLE aml.alerts (
    id         uuid        PRIMARY KEY,
    case_id    uuid        NOT NULL REFERENCES aml.cases (id),
    wallet_id  uuid        NOT NULL,
    rule_code  varchar(2)  NOT NULL,
    severity   varchar(8)  NOT NULL,
    details    text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_alerts_rule_code CHECK (rule_code IN ('R1', 'R2', 'R3', 'R4')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('MEDIUM', 'HIGH'))
);

CREATE INDEX ix_alerts_case ON aml.alerts (case_id);
CREATE INDEX ix_alerts_wallet_created ON aml.alerts (wallet_id, created_at);
