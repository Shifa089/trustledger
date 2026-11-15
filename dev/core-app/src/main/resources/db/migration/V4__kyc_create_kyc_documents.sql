-- KYC documents (SPEC §4, FR-KYC-2). Only metadata lives here; the file itself is in S3 under s3_key.
-- The original file name is not stored: it can contain personal data and nothing needs it.
-- content_type is what the magic bytes said, never what the client claimed.

CREATE TABLE kyc.kyc_documents (
    id           uuid         PRIMARY KEY,
    case_id      uuid         NOT NULL,
    doc_type     varchar(16)  NOT NULL,
    s3_key       varchar(200) NOT NULL,
    content_type varchar(32)  NOT NULL,
    size_bytes   bigint       NOT NULL,
    sha256       varchar(64)  NOT NULL,
    uploaded_by  uuid         NOT NULL,
    created_at   timestamptz  NOT NULL,
    -- Same schema (kyc), so a foreign key is allowed; there are none across module schemas.
    CONSTRAINT fk_kyc_documents_case FOREIGN KEY (case_id) REFERENCES kyc.kyc_cases (id),
    CONSTRAINT ck_kyc_documents_doc_type CHECK (doc_type IN ('PAN_CARD', 'ADDRESS_PROOF', 'PHOTO')),
    CONSTRAINT ck_kyc_documents_content_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    -- At most 5 MB (5 * 1024 * 1024 bytes), and never empty. The service checks first; this is the backstop.
    CONSTRAINT ck_kyc_documents_size CHECK (size_bytes BETWEEN 1 AND 5242880),
    -- Lower-case hex SHA-256 of the exact bytes stored in S3.
    CONSTRAINT ck_kyc_documents_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_kyc_documents_s3_key ON kyc.kyc_documents (s3_key);
CREATE INDEX ix_kyc_documents_case ON kyc.kyc_documents (case_id, created_at);

-- Compliance queue: FULL cases by status, oldest first.
CREATE INDEX ix_kyc_cases_queue ON kyc.kyc_cases (level, status, created_at);
