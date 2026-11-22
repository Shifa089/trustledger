-- FR-PR-2 (M10, Day 21): a customer's data export and erasure requests. Export: PENDING when asked for, then
-- COMPLETED with the JSON file's S3 key, or FAILED with a short reason code. Erasure (Session B) uses the same table.
--
-- Holds no personal data: the user is our user ID, the file lives in S3 (SSE-S3), and failure_reason is an error
-- code or exception class name, never a message. No foreign key to identity.users: other module's schema (CLAUDE.md).
CREATE TABLE privacy.data_requests (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    type           varchar(16)  NOT NULL,
    status         varchar(16)  NOT NULL,
    result_s3_key  varchar(256),
    failure_reason varchar(64),
    requested_at   timestamptz  NOT NULL,
    completed_at   timestamptz,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_data_requests_type CHECK (type IN ('EXPORT', 'ERASURE')),
    CONSTRAINT ck_data_requests_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    -- Only a completed export has a file; an erasure never has one.
    CONSTRAINT ck_data_requests_result CHECK ((result_s3_key IS NOT NULL) = (type = 'EXPORT' AND status = 'COMPLETED')),
    -- A finished request (either way) says when; only a failed one says why.
    CONSTRAINT ck_data_requests_completed CHECK ((status IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_data_requests_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_data_requests_times CHECK (completed_at IS NULL OR completed_at >= requested_at),
    CONSTRAINT uk_data_requests_result_key UNIQUE (result_s3_key)
);

-- At most one request of each type in progress per user: a double click or a racing second request is refused
-- (409 REQUEST_IN_PROGRESS) by the database, not only by the service's check.
CREATE UNIQUE INDEX uk_data_requests_one_pending ON privacy.data_requests (user_id, type) WHERE status = 'PENDING';
-- GET /me/data-requests: the user's requests, newest first.
CREATE INDEX ix_data_requests_user_created ON privacy.data_requests (user_id, created_at);

COMMENT ON TABLE privacy.data_requests IS 'FR-PR-2 data export and erasure requests; no personal data';
