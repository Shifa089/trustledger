-- Ledger module (SPEC §4, FR-L-1). One account per wallet (WALLET:<walletId>) plus platform accounts.
-- Accounts hold no balance column: a balance is always derived from journal_lines, so it can't drift.
-- The wallet's account is opened by the wallet module through LedgerApi when a wallet is created; there is no
-- foreign key to wallet.wallets (no FKs across module schemas).

CREATE TABLE ledger.ledger_accounts (
    id          uuid         PRIMARY KEY,
    code        varchar(64)  NOT NULL,
    type        varchar(16)  NOT NULL,
    normal_side varchar(6)   NOT NULL,
    currency    varchar(3)   NOT NULL,
    created_at  timestamptz  NOT NULL,
    CONSTRAINT uk_ledger_accounts_code UNIQUE (code),
    -- <KIND>:<id>, e.g. WALLET:0193f2...-... or PLATFORM:GATEWAY_CLEARING
    CONSTRAINT ck_ledger_accounts_code CHECK (code ~ '^[A-Z_]+:[A-Za-z0-9_-]+$'),
    CONSTRAINT ck_ledger_accounts_type CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE')),
    CONSTRAINT ck_ledger_accounts_normal_side CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    -- Accounting identity: assets grow with debits; liabilities and revenue grow with credits.
    CONSTRAINT ck_ledger_accounts_side_matches_type CHECK ((type = 'ASSET') = (normal_side = 'DEBIT')),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- Platform accounts, with fixed IDs so every environment has the same ones.
-- GATEWAY_CLEARING: money the gateway (Razorpay) has captured and owes the platform (an asset).
-- SUSPENSE: money received that can't be attributed yet; it is owed to someone (a liability).
INSERT INTO ledger.ledger_accounts (id, code, type, normal_side, currency, created_at) VALUES
    ('00000000-0000-7000-8000-000000000001', 'PLATFORM:GATEWAY_CLEARING', 'ASSET', 'DEBIT', 'INR', now()),
    ('00000000-0000-7000-8000-000000000002', 'PLATFORM:SUSPENSE', 'LIABILITY', 'CREDIT', 'INR', now());
