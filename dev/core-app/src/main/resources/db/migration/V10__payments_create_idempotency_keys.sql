-- Payments module: idempotency keys (SPEC §4, FR-P-1, "Transfer flow" step 1).
-- One row per (user, Idempotency-Key). The primary key is what makes a racing duplicate lose: the claim is
-- INSERT ... ON CONFLICT, so exactly one request per key gets to run the handler.
-- IN_PROGRESS: a request is running. COMPLETED: response_status/response_body hold the 2xx response to replay.
-- Rows are kept 24 hours (trustledger.payments.idempotency.ttl); an expired key may be claimed again, and
-- the hourly cleanup job deletes expired rows. user_id points to identity.users without a foreign key.

CREATE TABLE payments.idempotency_keys (
    user_id          uuid         NOT NULL,
    idem_key         uuid         NOT NULL,
    request_hash     char(64)     NOT NULL,
    status           varchar(16)  NOT NULL,
    response_status  smallint,
    response_body    jsonb,
    created_at       timestamptz  NOT NULL,
    expires_at       timestamptz  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, idem_key),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- SHA-256 of the canonical request body, lower-case hex.
    CONSTRAINT ck_idempotency_keys_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- A stored response exists exactly when the request completed (an empty 2xx body is allowed). Written per
    -- status, so an unknown status only fails ck_idempotency_keys_status. "IS NOT NULL" matters: a CHECK that
    -- evaluates to NULL passes, and NULL BETWEEN 200 AND 299 is NULL.
    CONSTRAINT ck_idempotency_keys_response CHECK (
        (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_status BETWEEN 200 AND 299))
        AND (status <> 'IN_PROGRESS' OR (response_status IS NULL AND response_body IS NULL))),
    CONSTRAINT ck_idempotency_keys_expiry CHECK (expires_at > created_at)
);

-- The cleanup job deletes by expiry.
CREATE INDEX ix_idempotency_keys_expires_at ON payments.idempotency_keys (expires_at);
