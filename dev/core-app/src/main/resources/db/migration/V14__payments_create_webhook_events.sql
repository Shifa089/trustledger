-- Payments module: incoming Razorpay webhooks (SPEC §4, FR-P-5). Every delivery is stored, accepted or not.
-- signature_valid = the delivery was accepted: its X-Razorpay-Signature matched AND it carried an event ID, an
-- event type and a JSON body. Rejected deliveries are kept as evidence and are never processed.
-- The payload is stored with personal fields (email, contact, vpa, card, ...) removed; we never need them.

CREATE TABLE payments.webhook_events (
    id               uuid         PRIMARY KEY,
    gateway_event_id varchar(64),
    event_type       varchar(64),
    payload          jsonb,
    signature_valid  boolean      NOT NULL,
    received_at      timestamptz  NOT NULL,
    processed_at     timestamptz,
    -- An accepted event always has what processing needs.
    CONSTRAINT ck_webhook_events_valid_complete CHECK (
        NOT signature_valid
        OR (gateway_event_id IS NOT NULL AND event_type IS NOT NULL AND payload IS NOT NULL)),
    -- A rejected delivery is never processed.
    CONSTRAINT ck_webhook_events_processed_valid CHECK (processed_at IS NULL OR signature_valid)
);

-- De-duplication by Razorpay's event ID (X-Razorpay-Event-Id), among ACCEPTED events only. A plain unique
-- constraint would let anyone block a genuine event: post a forged delivery with the real event's ID first, and
-- the genuine delivery would then look like a duplicate and be dropped. Rejected rows can't block anything.
CREATE UNIQUE INDEX ux_webhook_events_gateway_event
    ON payments.webhook_events (gateway_event_id) WHERE signature_valid;
