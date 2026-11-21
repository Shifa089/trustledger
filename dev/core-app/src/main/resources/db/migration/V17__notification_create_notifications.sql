-- Notifications sent for FR-N-1 (M8): one row per (event, recipient), whatever happened to it.
-- Holds no personal data: the recipient is our user ID, never the e-mail address, and the body is not stored
-- (it can be rendered again from the event). failure_reason is a short code or an exception class name, never an
-- exception message (an SMTP error may quote the address).
--
-- dedup_key makes delivery idempotent: an event republished after a restart (ADR 0005) finds its row and sends
-- nothing. Keys: TRANSFER_SENT:<transferId>, TRANSFER_RECEIVED:<transferId>, TOPUP_CAPTURED:<topupId>,
-- KYC_UPGRADED:<userId>:<level>, WALLET_FROZEN:<eventId>.
--
-- No foreign key to identity.users: other module's schema (CLAUDE.md).
CREATE TABLE notification.notifications (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    type           varchar(32)  NOT NULL,
    dedup_key      varchar(128) NOT NULL,
    status         varchar(16)  NOT NULL,
    failure_reason varchar(128),
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    sent_at        timestamptz,
    CONSTRAINT uk_notifications_dedup_key UNIQUE (dedup_key),
    CONSTRAINT ck_notifications_type CHECK (type IN
        ('TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TOPUP_CAPTURED', 'KYC_UPGRADED', 'WALLET_FROZEN')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    -- SENT has a send time and no failure; FAILED and SKIPPED say why; PENDING has neither.
    CONSTRAINT ck_notifications_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_notifications_reason CHECK ((status IN ('FAILED', 'SKIPPED')) = (failure_reason IS NOT NULL))
);

-- "What did we send this user?" (support, and the statements/history work later).
CREATE INDEX ix_notifications_user ON notification.notifications (user_id, created_at);
-- Retrying failed notifications (a later session) scans by status.
CREATE INDEX ix_notifications_status ON notification.notifications (status) WHERE status = 'FAILED';

COMMENT ON TABLE notification.notifications IS 'FR-N-1 notifications: one row per event and recipient, no personal data';
