-- KYC module (SPEC §4, FR-KYC-1, FR-KYC-3, FR-PR-1). Documents (kyc.kyc_documents) follow in M3 Session B.
-- Personal data only as AES-256-GCM ciphertext (*_enc). pan_hash is an HMAC blind index so a PAN can be
-- found (and, later, de-duplicated across accounts) without storing it in clear.
-- user_id points to identity.users, but there is no foreign key: no FKs across module schemas.

CREATE TABLE kyc.kyc_cases (
    id               uuid         PRIMARY KEY,
    user_id          uuid         NOT NULL,
    level            varchar(8)   NOT NULL,
    status           varchar(16)  NOT NULL,
    -- Name as on the PAN card (MIN); not the Keycloak profile name.
    full_name_enc    text,
    pan_enc          text,
    pan_hash         varchar(64),
    dob_enc          text,
    address_enc      text,
    verified_by      uuid,
    verified_at      timestamptz,
    approved_by      uuid,
    approved_at      timestamptz,
    rejected_by      uuid,
    rejected_at      timestamptz,
    rejection_reason varchar(500),
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL,
    CONSTRAINT ck_kyc_cases_level CHECK (level IN ('MIN', 'FULL')),
    CONSTRAINT ck_kyc_cases_status CHECK (status IN ('SUBMITTED', 'VERIFIED', 'APPROVED', 'REJECTED')),
    -- Maker-checker (FR-KYC-3): the approver can never be the officer who verified. Enforced in the
    -- KycCase aggregate too; this is the last line of defence against a bug or a manual UPDATE.
    CONSTRAINT ck_kyc_cases_maker_checker CHECK (approved_by IS NULL OR approved_by <> verified_by),
    -- A FULL case is APPROVED only after both steps; MIN is approved by the system (both stay NULL).
    CONSTRAINT ck_kyc_cases_full_four_eyes CHECK (
        level = 'MIN' OR status <> 'APPROVED' OR (verified_by IS NOT NULL AND approved_by IS NOT NULL)),
    -- A rejection always carries its reason, and only a rejection does.
    CONSTRAINT ck_kyc_cases_rejection_reason CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CONSTRAINT ck_kyc_cases_pan_pair CHECK ((pan_enc IS NULL) = (pan_hash IS NULL))
);

-- At most one live (not rejected) case per user and level: a second POST /kyc/min, even two racing
-- each other, cannot create a second MIN case. A rejected FULL case may be followed by a new one.
CREATE UNIQUE INDEX uk_kyc_cases_user_level_live ON kyc.kyc_cases (user_id, level) WHERE status <> 'REJECTED';

CREATE INDEX ix_kyc_cases_user ON kyc.kyc_cases (user_id, created_at);
CREATE INDEX ix_kyc_cases_pan_hash ON kyc.kyc_cases (pan_hash);
