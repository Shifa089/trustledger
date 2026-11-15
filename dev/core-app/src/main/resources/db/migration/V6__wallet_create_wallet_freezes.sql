-- Freeze history (SPEC §4, FR-W-3): one row per freeze, closed by the unfreeze. Kept for audits.
-- source COMPLIANCE = an officer (frozen_by set); AML = the AML service (M9, frozen_by may be NULL).
-- Not in SPEC §4: unfreeze_reason, because an unfreeze needs a reason as much as a freeze does.

CREATE TABLE wallet.wallet_freezes (
    id              uuid         PRIMARY KEY,
    wallet_id       uuid         NOT NULL,
    source          varchar(16)  NOT NULL,
    reason          varchar(500) NOT NULL,
    frozen_by       uuid,
    frozen_at       timestamptz  NOT NULL,
    unfrozen_by     uuid,
    unfrozen_at     timestamptz,
    unfreeze_reason varchar(500),
    created_at      timestamptz  NOT NULL,
    -- Same schema, so a foreign key is allowed (the no-FK rule is about other modules' schemas).
    CONSTRAINT fk_wallet_freezes_wallet FOREIGN KEY (wallet_id) REFERENCES wallet.wallets (id),
    CONSTRAINT ck_wallet_freezes_source CHECK (source IN ('COMPLIANCE', 'AML')),
    CONSTRAINT ck_wallet_freezes_reason CHECK (btrim(reason) <> ''),
    -- An officer's freeze always records the officer.
    CONSTRAINT ck_wallet_freezes_officer CHECK (source <> 'COMPLIANCE' OR frozen_by IS NOT NULL),
    -- An unfreeze is all or nothing: who, when and why.
    CONSTRAINT ck_wallet_freezes_unfreeze_complete CHECK (
        (unfrozen_at IS NULL) = (unfrozen_by IS NULL) AND (unfrozen_at IS NULL) = (unfreeze_reason IS NULL)),
    CONSTRAINT ck_wallet_freezes_unfreeze_reason CHECK (unfreeze_reason IS NULL OR btrim(unfreeze_reason) <> ''),
    CONSTRAINT ck_wallet_freezes_unfreeze_after_freeze CHECK (unfrozen_at IS NULL OR unfrozen_at >= frozen_at)
);

-- At most one open freeze per wallet.
CREATE UNIQUE INDEX uk_wallet_freezes_one_open ON wallet.wallet_freezes (wallet_id) WHERE unfrozen_at IS NULL;

CREATE INDEX ix_wallet_freezes_wallet ON wallet.wallet_freezes (wallet_id, frozen_at);
