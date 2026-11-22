-- FR-PR-2 erasure, privacy side (M10 Day 21 B).

-- 1. core-app's own copy of the AML service's case lifecycle (AmlCaseStatusChangedV1), so erasure can be refused while
--    the user's wallet has an open case without calling the AML service. One row per case; "open" only ever goes
--    from true to false (a finished case never reopens), so applying the events in any order, or twice, ends in the
--    same state. No foreign key: the wallet lives in another module's schema, the case in another service.
CREATE TABLE privacy.aml_cases (
    case_id    uuid        PRIMARY KEY,
    wallet_id  uuid        NOT NULL,
    open       boolean     NOT NULL,
    -- When the AML service recorded the latest change we applied (its clock).
    changed_at timestamptz NOT NULL,
    -- When this row was last written here (our clock).
    updated_at timestamptz NOT NULL
);

-- The erasure check: "does this wallet have an open case?". Partial, so it holds only the open ones.
CREATE INDEX ix_aml_cases_open_wallet ON privacy.aml_cases (wallet_id) WHERE open;

-- 2. A user is erased once: two racing erasure requests can't both complete.
CREATE UNIQUE INDEX uk_data_requests_one_erasure ON privacy.data_requests (user_id)
    WHERE type = 'ERASURE' AND status = 'COMPLETED';
