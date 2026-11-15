package com.trustledger.core.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V4 migration: the database refuses bad document metadata even from raw SQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KycDocumentMigrationIT {

    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final String SHA = "c".repeat(64);

    @Autowired
    JdbcTemplate jdbc;

    UUID caseId;

    @BeforeEach
    void setUp() {
        caseId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into kyc.kyc_cases (id, user_id, level, status, address_enc, created_at, updated_at, version)
                values (?, ?, 'FULL', 'SUBMITTED', 'ciphertext', ?, ?, 0)""", caseId, UUID.randomUUID(), now, now);
    }

    @Test
    void validRowIsAccepted() {
        insert(caseId, "PAN_CARD", "application/pdf", FIVE_MB, SHA, key());

        assertThat(jdbc.queryForObject("select count(*) from kyc.kyc_documents where case_id = ?", Integer.class,
                caseId)).isEqualTo(1);
    }

    @Test
    void moreThanFiveMegabytesOrEmptyIsRejected() {
        assertRejected(() -> insert(caseId, "PAN_CARD", "application/pdf", FIVE_MB + 1, SHA, key()),
                "ck_kyc_documents_size");
        assertRejected(() -> insert(caseId, "PAN_CARD", "application/pdf", 0, SHA, key()), "ck_kyc_documents_size");
    }

    @Test
    void onlyPdfJpegAndPngAreAccepted() {
        assertRejected(() -> insert(caseId, "PAN_CARD", "application/x-msdownload", 10, SHA, key()),
                "ck_kyc_documents_content_type");
        assertRejected(() -> insert(caseId, "PAN_CARD", "image/gif", 10, SHA, key()), "ck_kyc_documents_content_type");
    }

    @Test
    void unknownDocumentTypeIsRejected() {
        assertRejected(() -> insert(caseId, "PASSPORT", "image/png", 10, SHA, key()), "ck_kyc_documents_doc_type");
    }

    @Test
    void shaMustBeLowerCaseHexOf64Characters() {
        assertRejected(() -> insert(caseId, "PAN_CARD", "image/png", 10, "C".repeat(64), key()),
                "ck_kyc_documents_sha256");
        assertRejected(() -> insert(caseId, "PAN_CARD", "image/png", 10, "c".repeat(63), key()),
                "ck_kyc_documents_sha256");
    }

    @Test
    void documentMustBelongToAnExistingCase() {
        assertRejected(() -> insert(UUID.randomUUID(), "PAN_CARD", "image/png", 10, SHA, key()),
                "fk_kyc_documents_case");
    }

    @Test
    void s3KeyIsUnique() {
        String key = key();
        insert(caseId, "PAN_CARD", "image/png", 10, SHA, key);

        assertRejected(() -> insert(caseId, "PHOTO", "image/png", 10, SHA, key), "uk_kyc_documents_s3_key");
    }

    private void insert(UUID forCase, String type, String contentType, long size, String sha, String key) {
        jdbc.update("""
                insert into kyc.kyc_documents
                    (id, case_id, doc_type, s3_key, content_type, size_bytes, sha256, uploaded_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), forCase, type, key, contentType, size, sha, UUID.randomUUID(),
                Timestamp.from(Instant.now()));
    }

    private String key() {
        return "kyc/" + caseId + "/" + UUID.randomUUID();
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
