-- Wallet module (SPEC §4, FR-W-1, FR-W-2). One wallet per user, created when the user reaches MIN KYC.
-- Money is bigint paise. balance_minor is a cached balance: from M5 the ledger is the source of truth and
-- the nightly reconciliation compares the two. available = balance_minor - held_minor (FR-W-2).
-- user_id points to identity.users, but there is no foreign key: no FKs across module schemas.

CREATE TABLE wallet.wallets (
    id                uuid         PRIMARY KEY,
    user_id           uuid         NOT NULL,
    tier              varchar(8)   NOT NULL,
    status            varchar(16)  NOT NULL,
    balance_minor     bigint       NOT NULL,
    held_minor        bigint       NOT NULL,
    currency          varchar(3)   NOT NULL,
    -- TODO M5: set when the ledger creates the WALLET:<id> account (it listens to WalletCreated).
    ledger_account_id uuid,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL,
    -- One wallet per user: also what makes wallet creation idempotent when two events race.
    CONSTRAINT uk_wallets_user UNIQUE (user_id),
    CONSTRAINT ck_wallets_tier CHECK (tier IN ('MIN', 'FULL')),
    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_wallets_currency CHECK (currency = 'INR'),
    -- The Wallet aggregate enforces these too; the database is the last line of defence against a bug or
    -- a manual UPDATE.
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_minor >= 0),
    CONSTRAINT ck_wallets_held_non_negative CHECK (held_minor >= 0),
    CONSTRAINT ck_wallets_held_within_balance CHECK (held_minor <= balance_minor)
);
