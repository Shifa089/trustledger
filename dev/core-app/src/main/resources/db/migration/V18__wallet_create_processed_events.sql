-- FR-AML-3: the wallet module's inbox for Kafka events it consumes (AccountFreezeRequestedV1 from risk-aml-service).
-- Inserted with ON CONFLICT DO NOTHING in the same transaction as the freeze: a redelivered event finds its row and
-- changes nothing (ADR 0007 "Freeze requests"). Kept as an audit of what was consumed and when.
CREATE TABLE wallet.processed_events (
    event_id     uuid         PRIMARY KEY,
    topic        varchar(200) NOT NULL,
    processed_at timestamptz  NOT NULL
);
