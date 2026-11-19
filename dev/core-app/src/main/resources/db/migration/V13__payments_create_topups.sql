-- Payments module: top-ups through Razorpay (SPEC §4, FR-P-4 to FR-P-6).
-- CREATED -> PENDING -> CAPTURED | FAILED | EXPIRED; CREATED -> FAILED when the gateway order could not be created.
-- The wallet and journal IDs point into other module schemas, so there are no foreign keys. Money is bigint paise.

CREATE TABLE payments.topups (
    id                 uuid         PRIMARY KEY,
    wallet_id          uuid         NOT NULL,
    amount_minor       bigint       NOT NULL,
    currency           varchar(3)   NOT NULL,
    gateway_order_id   varchar(64),
    gateway_payment_id varchar(64),
    status             varchar(20)  NOT NULL,
    journal_entry_id   uuid,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL,
    CONSTRAINT ck_topups_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_topups_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_topups_status CHECK (status IN ('CREATED', 'PENDING', 'CAPTURED', 'FAILED', 'EXPIRED')),
    -- A gateway order exists from PENDING on; a CREATED top-up has none yet. FAILED may have one or not
    -- (the order call failed, or the payment failed later).
    CONSTRAINT ck_topups_order_id CHECK (
        (status <> 'CREATED' OR gateway_order_id IS NULL)
        AND (status NOT IN ('PENDING', 'CAPTURED', 'EXPIRED') OR gateway_order_id IS NOT NULL)),
    -- Money reached the wallet exactly when the top-up is CAPTURED, and then we know which payment did it.
    CONSTRAINT ck_topups_journal_entry CHECK ((status = 'CAPTURED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT ck_topups_payment_id CHECK ((status = 'CAPTURED') = (gateway_payment_id IS NOT NULL)),
    CONSTRAINT uk_topups_gateway_order UNIQUE (gateway_order_id),
    CONSTRAINT uk_topups_gateway_payment UNIQUE (gateway_payment_id),
    CONSTRAINT uk_topups_journal_entry UNIQUE (journal_entry_id)
);

-- Monthly load and open-top-up sums (wallet + time window).
CREATE INDEX ix_topups_wallet_created ON payments.topups (wallet_id, created_at);
