package com.trustledger.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V21: the database refuses a data request in an impossible state, even when the application is bypassed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PrivacyMigrationIT {

    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void validRowsOfEveryKindAreAccepted() {
        UUID user = UUID.randomUUID();
        insert(user, "EXPORT", "PENDING", null, null, null);
        insert(user, "EXPORT", "COMPLETED", "exports/" + UUID.randomUUID() + ".json", null, T0);
        insert(user, "EXPORT", "FAILED", null, "STORAGE_UNAVAILABLE", T0);
        insert(user, "ERASURE", "PENDING", null, null, null);
        insert(user, "ERASURE", "COMPLETED", null, null, T0.plusSeconds(1));

        assertThat(jdbc.queryForObject("select count(*) from privacy.data_requests where user_id = ?", Long.class,
                user)).isEqualTo(5);
    }

    @Test
    void onlyOnePendingRequestOfEachTypePerUser() {
        UUID user = UUID.randomUUID();
        insert(user, "EXPORT", "PENDING", null, null, null);

        assertRefused(() -> insert(user, "EXPORT", "PENDING", null, null, null));
        // Another type, another user, or a finished request of the same type: all fine.
        insert(user, "ERASURE", "PENDING", null, null, null);
        insert(UUID.randomUUID(), "EXPORT", "PENDING", null, null, null);
        insert(user, "EXPORT", "FAILED", null, "NOT_FOUND", T0);
    }

    @Test
    void aResultKeyExactlyOnACompletedExport() {
        UUID user = UUID.randomUUID();
        assertRefused(() -> insert(user, "EXPORT", "COMPLETED", null, null, T0));
        assertRefused(() -> insert(user, "EXPORT", "PENDING", "exports/x.json", null, null));
        assertRefused(() -> insert(user, "ERASURE", "COMPLETED", "exports/y.json", null, T0));
    }

    @Test
    void aFinishedRequestHasACompletionTimeAndOnlyAFailedOneAReason() {
        UUID user = UUID.randomUUID();
        assertRefused(() -> insert(user, "EXPORT", "FAILED", null, "NOT_FOUND", null));
        assertRefused(() -> insert(user, "EXPORT", "FAILED", null, null, T0));
        assertRefused(() -> insert(user, "EXPORT", "PENDING", null, null, T0));
        assertRefused(() -> insert(user, "EXPORT", "COMPLETED", "exports/z.json", "NOT_FOUND", T0));
        assertRefused(() -> insert(user, "EXPORT", "COMPLETED", "exports/w.json", null, T0.minusSeconds(1)));
    }

    @Test
    void unknownTypeOrStatusIsRefused() {
        UUID user = UUID.randomUUID();
        assertRefused(() -> insert(user, "DELETE", "PENDING", null, null, null));
        assertRefused(() -> insert(user, "EXPORT", "RUNNING", null, null, null));
    }

    @Test
    void aResultKeyIsUsedOnce() {
        String key = "exports/" + UUID.randomUUID() + ".json";
        insert(UUID.randomUUID(), "EXPORT", "COMPLETED", key, null, T0);

        assertRefused(() -> insert(UUID.randomUUID(), "EXPORT", "COMPLETED", key, null, T0));
    }

    @Test
    void theReceiverIndexForTheExportExists() {
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = 'payments'"
                + " and indexname = 'ix_transfers_receiver_created'", Long.class)).isEqualTo(1);
    }

    // --- V23 / V24: erasure ---------------------------------------------------------------------------------------

    @Test
    void aUserIsErasedOnce() {
        UUID user = UUID.randomUUID();
        insert(user, "ERASURE", "COMPLETED", null, null, T0);

        assertRefused(() -> insert(user, "ERASURE", "COMPLETED", null, null, T0));
        // A failed one (none today, but allowed) or another user's is fine.
        insert(user, "ERASURE", "FAILED", null, "NOT_FOUND", T0);
        insert(UUID.randomUUID(), "ERASURE", "COMPLETED", null, null, T0);
    }

    @Test
    void anErasedUserHoldsNoPersonalDataOrBlindIndex() {
        UUID user = insertUser("ACTIVE", true);

        assertRefused(() -> jdbc.update("update identity.users set status = 'ERASED' where id = ?", user));
        UUID nameOnly = insertUser("ACTIVE", false);
        jdbc.update("update identity.users set full_name_enc = 'x' where id = ?", nameOnly);
        assertRefused(() -> jdbc.update("update identity.users set status = 'ERASED' where id = ?", nameOnly));
        jdbc.update("""
                update identity.users set status = 'ERASED', email_enc = null, email_hash = null, phone_enc = null,
                    phone_hash = null, full_name_enc = null where id = ?""", user);
        assertRefused(() -> jdbc.update("update identity.users set phone_enc = 'x', phone_hash = ? where id = ?",
                "c".repeat(64), user));
    }

    @Test
    void consentsAllowOnlyBlankingTheIp() {
        UUID user = insertUser("ACTIVE", false);
        UUID consent = UUID.randomUUID();
        jdbc.update("""
                insert into identity.consents (id, user_id, purpose, policy_version, granted, captured_at, ip, created_at)
                values (?, ?, 'TERMS', '2026-11-01', true, ?, '203.0.113.7', ?)""",
                consent, user, Timestamp.from(T0), Timestamp.from(T0));

        assertRefused(() -> jdbc.update("update identity.consents set granted = false where id = ?", consent));
        assertRefused(() -> jdbc.update("update identity.consents set ip = '198.51.100.1' where id = ?", consent));
        assertRefused(() -> jdbc.update("update identity.consents set ip = null, granted = false where id = ?",
                consent));
        assertRefused(() -> jdbc.update("delete from identity.consents where id = ?", consent));

        assertThat(jdbc.update("update identity.consents set ip = null where id = ?", consent)).isEqualTo(1);
        // And only once: a null IP can't be changed back.
        assertRefused(() -> jdbc.update("update identity.consents set ip = '203.0.113.7' where id = ?", consent));
    }

    @Test
    void theOpenCaseIndexExists() {
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = 'privacy'"
                + " and indexname = 'ix_aml_cases_open_wallet'", Long.class)).isEqualTo(1);
    }

    /** A user straight in SQL; {@code withPersonalData} sets e-mail and phone with their blind indexes. */
    private UUID insertUser(String status, boolean withPersonalData) {
        UUID id = UUID.randomUUID();
        String hash = withPersonalData ? UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "") : null;
        jdbc.update("""
                insert into identity.users (id, keycloak_subject, email_enc, email_hash, phone_enc, phone_hash,
                    full_name_enc, status, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, null, ?, ?, ?, 0)""",
                id, "sub-" + id, withPersonalData ? "x" : null, hash, withPersonalData ? "x" : null, hash, status,
                Timestamp.from(T0), Timestamp.from(T0));
        return id;
    }

    private void insert(UUID user, String type, String status, String key, String reason, Instant completedAt) {
        jdbc.update("""
                insert into privacy.data_requests (id, user_id, type, status, result_s3_key, failure_reason,
                    requested_at, completed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                UUID.randomUUID(), user, type, status, key, reason, Timestamp.from(T0),
                completedAt == null ? null : Timestamp.from(completedAt), Timestamp.from(T0), Timestamp.from(T0));
    }

    private static void assertRefused(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}
