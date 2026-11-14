-- Identity module (SPEC §4, FR-ID-2, FR-ID-3, FR-PR-1).
-- Personal data is stored only encrypted (*_enc, AES-256-GCM, base64 text). Columns that must be
-- searchable get an HMAC-SHA256 blind index (*_hash, 64 hex chars) next to them.

CREATE TABLE identity.users (
    id               uuid         PRIMARY KEY,
    -- Keycloak "sub". Unique: two first requests racing each other insert only one row (JIT).
    keycloak_subject varchar(255) NOT NULL,
    email_enc        text,
    email_hash       varchar(64),
    phone_enc        text,
    phone_hash       varchar(64),
    full_name_enc    text,
    status           varchar(16)  NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL,
    CONSTRAINT uk_users_keycloak_subject UNIQUE (keycloak_subject),
    -- One account per mobile number. NULLs do not collide, so users without a phone are fine.
    CONSTRAINT uk_users_phone_hash UNIQUE (phone_hash),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'ERASED')),
    -- Ciphertext and its blind index exist together or not at all.
    CONSTRAINT ck_users_email_pair CHECK ((email_enc IS NULL) = (email_hash IS NULL)),
    CONSTRAINT ck_users_phone_pair CHECK ((phone_enc IS NULL) = (phone_hash IS NULL))
);

-- Lookup by e-mail (not unique: Keycloak owns e-mail uniqueness, and it can change there).
CREATE INDEX ix_users_email_hash ON identity.users (email_hash);

-- Append-only consent history. The latest row (highest seq) per (user, purpose) is the current state.
CREATE TABLE identity.consents (
    id             uuid        PRIMARY KEY,
    -- Insertion order: decides "latest" exactly, even for two rows with the same captured_at.
    seq            bigint      GENERATED ALWAYS AS IDENTITY,
    user_id        uuid        NOT NULL REFERENCES identity.users (id),
    purpose        varchar(16) NOT NULL,
    policy_version varchar(32) NOT NULL,
    granted        boolean     NOT NULL,
    captured_at    timestamptz NOT NULL,
    -- IPv4 or IPv6 text form (max 45 chars).
    ip             varchar(45),
    created_at     timestamptz NOT NULL,
    CONSTRAINT uk_consents_seq UNIQUE (seq),
    CONSTRAINT ck_consents_purpose CHECK (purpose IN ('TERMS', 'PRIVACY', 'MARKETING'))
);

CREATE INDEX ix_consents_user_purpose_seq ON identity.consents (user_id, purpose, seq DESC);

-- Consent records are evidence: the database refuses to change or delete them.
-- (Erasure in M10 may need to blank the IP; that migration will relax this deliberately.)
CREATE FUNCTION identity.reject_consent_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'identity.consents is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_consents_append_only
    BEFORE UPDATE OR DELETE ON identity.consents
    FOR EACH ROW EXECUTE FUNCTION identity.reject_consent_mutation();

CREATE TRIGGER trg_consents_no_truncate
    BEFORE TRUNCATE ON identity.consents
    FOR EACH STATEMENT EXECUTE FUNCTION identity.reject_consent_mutation();
