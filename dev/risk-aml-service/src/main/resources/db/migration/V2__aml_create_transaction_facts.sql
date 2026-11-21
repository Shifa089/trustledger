-- What the rules (R1-R4, FR-AML-1) look at: one row per wallet per money movement. A transfer gives two facts
-- (OUT for the sender, IN for the receiver) from one event, so (event_id, direction) is unique, not event_id alone.
-- IDs and amounts only: no names, no notes (the contracts carry none).
CREATE TABLE aml.transaction_facts (
    id                     uuid        PRIMARY KEY,
    event_id               uuid        NOT NULL,
    wallet_id              uuid        NOT NULL,
    counterparty_wallet_id uuid,
    direction              varchar(3)  NOT NULL,
    source                 varchar(16) NOT NULL,
    reference_id           uuid        NOT NULL,
    amount_minor           bigint      NOT NULL,
    currency               varchar(3)  NOT NULL,
    occurred_at            timestamptz NOT NULL,
    created_at             timestamptz NOT NULL,
    CONSTRAINT uq_transaction_facts_event_direction UNIQUE (event_id, direction),
    CONSTRAINT ck_transaction_facts_direction CHECK (direction IN ('OUT', 'IN')),
    CONSTRAINT ck_transaction_facts_source CHECK (source IN ('TRANSFER', 'TOPUP')),
    CONSTRAINT ck_transaction_facts_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_transaction_facts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- A transfer always names the other wallet; a top-up comes from the gateway, always IN, no counterparty.
    CONSTRAINT ck_transaction_facts_counterparty CHECK (
        (source = 'TRANSFER' AND counterparty_wallet_id IS NOT NULL AND counterparty_wallet_id <> wallet_id)
        OR (source = 'TOPUP' AND direction = 'IN' AND counterparty_wallet_id IS NULL))
);

-- Rule windows: "this wallet's facts in the last 10 minutes / 24 hours".
CREATE INDEX ix_transaction_facts_wallet_occurred ON aml.transaction_facts (wallet_id, occurred_at);

-- Facts are evidence for alerts and STR reports: append-only, like the ledger journal in core-app.
CREATE FUNCTION aml.reject_fact_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'aml.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_transaction_facts_append_only
    BEFORE UPDATE OR DELETE ON aml.transaction_facts
    FOR EACH ROW EXECUTE FUNCTION aml.reject_fact_mutation();

CREATE TRIGGER trg_transaction_facts_no_truncate
    BEFORE TRUNCATE ON aml.transaction_facts
    FOR EACH STATEMENT EXECUTE FUNCTION aml.reject_fact_mutation();
