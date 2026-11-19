-- Payments module: P2P transfers (SPEC §4, FR-P-1 to FR-P-3).
-- INITIATED -> COMPLETED | HELD_FOR_REVIEW | FAILED; HELD_FOR_REVIEW -> COMPLETED | REJECTED.
-- REVERSED is reserved for a later correction flow. The wallet and ledger IDs point into other module schemas,
-- so there are no foreign keys (no FKs across module schemas). Money is bigint paise.

CREATE TABLE payments.transfers (
    id                 uuid         PRIMARY KEY,
    sender_wallet_id   uuid         NOT NULL,
    receiver_wallet_id uuid         NOT NULL,
    amount_minor       bigint       NOT NULL,
    currency           varchar(3)   NOT NULL,
    note               varchar(140),
    status             varchar(20)  NOT NULL,
    journal_entry_id   uuid,
    reviewed_by        uuid,
    reviewed_at        timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL,
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_transfers_not_self CHECK (sender_wallet_id <> receiver_wallet_id),
    CONSTRAINT ck_transfers_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_transfers_status CHECK (status IN
        ('INITIATED', 'HELD_FOR_REVIEW', 'COMPLETED', 'REJECTED', 'FAILED', 'REVERSED')),
    -- Money moved in the ledger exactly when the transfer completed (or was later reversed).
    CONSTRAINT ck_transfers_journal_entry CHECK (
        (status IN ('COMPLETED', 'REVERSED')) = (journal_entry_id IS NOT NULL)),
    CONSTRAINT uk_transfers_journal_entry UNIQUE (journal_entry_id),
    -- A review records who and when together; a rejection always has a reviewer.
    CONSTRAINT ck_transfers_review_pair CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    CONSTRAINT ck_transfers_rejected_reviewed CHECK (status <> 'REJECTED' OR reviewed_by IS NOT NULL)
);

-- Daily and monthly limit sums (sender + time window) and the sender's history.
CREATE INDEX ix_transfers_sender_created ON payments.transfers (sender_wallet_id, created_at);
