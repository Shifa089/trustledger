-- Idempotent consumer (SPEC §4, M9): one row per Kafka event this service has handled. The row is inserted in the
-- same transaction as the event's effects, so "processed" and "facts stored" commit or roll back together, and a
-- redelivered event (a retry, a rebalance, a crash before the offset commit) is skipped.
CREATE TABLE aml.processed_events (
    event_id     uuid        PRIMARY KEY,
    topic        text        NOT NULL,
    processed_at timestamptz NOT NULL
);
