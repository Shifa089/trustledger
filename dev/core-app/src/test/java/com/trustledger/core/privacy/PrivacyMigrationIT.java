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
