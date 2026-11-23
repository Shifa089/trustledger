package com.trustledger.core.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3 migration: the database itself enforces maker-checker, rejection reasons and one live case per
 * level, even for a raw SQL statement that bypasses the KycCase aggregate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KycMigrationIT {

    private static final String PAN_HASH = "b".repeat(64);

    @Autowired
    JdbcTemplate jdbc;

    /** Raw rows this test wrote; removed after each test so they never show up in the officers' queues. */
    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void removeRawCases() {
        inserted.forEach(id -> jdbc.update("delete from kyc.kyc_cases where id = ?", id));
    }

    @Test
    void sameOfficerAsMakerAndCheckerIsRejectedByTheDatabase() {
        UUID officer = UUID.randomUUID();

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "FULL", "APPROVED", officer, officer, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_kyc_cases_maker_checker");
    }

    @Test
    void sameOfficerCannotBeWrittenByALaterUpdateEither() {
        UUID officer = UUID.randomUUID();
        UUID id = insert(UUID.randomUUID(), "FULL", "VERIFIED", officer, null, null);

        assertThatThrownBy(() -> jdbc.update(
                "update kyc.kyc_cases set status = 'APPROVED', approved_by = verified_by where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_kyc_cases_maker_checker");
    }

    @Test
    void twoDifferentOfficersAreAccepted() {
        UUID id = insert(UUID.randomUUID(), "FULL", "APPROVED", UUID.randomUUID(), UUID.randomUUID(), null);

        assertThat(jdbc.queryForObject("select status from kyc.kyc_cases where id = ?", String.class, id))
                .isEqualTo("APPROVED");
    }

    @Test
    void fullCaseCannotBeApprovedWithoutBothOfficers() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "FULL", "APPROVED", UUID.randomUUID(), null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_kyc_cases_full_four_eyes");
    }

    @Test
    void minCaseIsApprovedWithoutOfficers() {
        UUID id = insert(UUID.randomUUID(), "MIN", "APPROVED", null, null, null);

        assertThat(jdbc.queryForObject("select level from kyc.kyc_cases where id = ?", String.class, id))
                .isEqualTo("MIN");
    }

    @Test
    void rejectionNeedsAReasonAndOnlyARejectionHasOne() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "FULL", "REJECTED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_kyc_cases_rejection_reason");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "FULL", "SUBMITTED", null, null, "Why?"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_kyc_cases_rejection_reason");
    }

    @Test
    void unknownLevelOrStatusIsRejected() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "MAX", "SUBMITTED", null, null, null))
                .hasMessageContaining("ck_kyc_cases_level");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "MIN", "PENDING", null, null, null))
                .hasMessageContaining("ck_kyc_cases_status");
    }

    @Test
    void oneLiveCasePerUserAndLevelButARejectedCaseMayBeFollowedByANewOne() {
        UUID user = UUID.randomUUID();
        insert(user, "FULL", "REJECTED", null, null, "Blurry document");
        insert(user, "FULL", "SUBMITTED", null, null, null);
        insert(user, "MIN", "APPROVED", null, null, null); // other level: fine

        assertThatThrownBy(() -> insert(user, "FULL", "SUBMITTED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_kyc_cases_user_level_live");
    }

    @Test
    void panCiphertextAndBlindIndexComeTogether() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into kyc.kyc_cases (id, user_id, level, status, pan_enc, created_at, updated_at, version)
                values (?, ?, 'MIN', 'SUBMITTED', 'ciphertext', now(), now(), 0)""",
                UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("ck_kyc_cases_pan_pair");
    }

    private UUID insert(UUID userId, String level, String status, UUID verifiedBy, UUID approvedBy, String reason) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        boolean min = "MIN".equals(level);
        jdbc.update("""
                insert into kyc.kyc_cases (id, user_id, level, status, pan_enc, pan_hash, verified_by, approved_by,
                    rejection_reason, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, userId, level, status, min ? "ciphertext" : null, min ? PAN_HASH : null, verifiedBy, approvedBy,
                reason, now, now);
        inserted.add(id);
        return id;
    }
}
