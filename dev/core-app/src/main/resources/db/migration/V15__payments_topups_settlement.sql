-- Payments module: settling top-ups (M7 part 3b, FR-P-4 and FR-P-6, ADR 0006).
-- PARKED: the payment was captured but the wallet could not take it (over a limit, frozen or closed); the money is
-- booked GATEWAY_CLEARING -> SUSPENSE instead of GATEWAY_CLEARING -> WALLET. Like CAPTURED it has a gateway order,
-- the payment that paid it and a journal entry. A captured payment may also settle a FAILED or EXPIRED top-up (late
-- capture); the order-ID rule already allows that.

ALTER TABLE payments.topups
    DROP CONSTRAINT ck_topups_status,
    DROP CONSTRAINT ck_topups_order_id,
    DROP CONSTRAINT ck_topups_journal_entry,
    DROP CONSTRAINT ck_topups_payment_id;

ALTER TABLE payments.topups
    ADD CONSTRAINT ck_topups_status
        CHECK (status IN ('CREATED', 'PENDING', 'CAPTURED', 'PARKED', 'FAILED', 'EXPIRED')),
    ADD CONSTRAINT ck_topups_order_id CHECK (
        (status <> 'CREATED' OR gateway_order_id IS NULL)
        AND (status NOT IN ('PENDING', 'CAPTURED', 'PARKED', 'EXPIRED') OR gateway_order_id IS NOT NULL)),
    -- Money was booked (to the wallet or to suspense) exactly when the top-up is CAPTURED or PARKED.
    ADD CONSTRAINT ck_topups_journal_entry
        CHECK ((status IN ('CAPTURED', 'PARKED')) = (journal_entry_id IS NOT NULL)),
    ADD CONSTRAINT ck_topups_payment_id
        CHECK ((status IN ('CAPTURED', 'PARKED')) = (gateway_payment_id IS NOT NULL));

-- PendingTopupReconciler (FR-P-6): PENDING top-ups older than 15 minutes, oldest first.
CREATE INDEX ix_topups_pending_created ON payments.topups (created_at) WHERE status = 'PENDING';
